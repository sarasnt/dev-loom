package com.devloom.briefing;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.devloom.ai.HostAgentClient;
import com.devloom.common.AppConfigService;
import com.devloom.workmodel.WorkItemEntity;
import com.devloom.workmodel.WorkItemRepository;

/**
 * Sends the daily digest (scheduled) and urgent alerts (on sync) as desktop notifications via the
 * host agent (spec §8). Quiet hours gate both; overnight urgent items surface in the morning
 * digest. All delivery is best-effort — a notify failure never breaks a request or a sync.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final BriefingService briefing;
    private final WorkItemRepository work;
    private final HostAgentClient agent;
    private final AppConfigService cfg;
    private final ZoneId zone = ZoneId.systemDefault(); // container TZ is set via compose
    private volatile LocalDate lastDigestDate = null;

    public NotificationService(BriefingService briefing, WorkItemRepository work,
                               HostAgentClient agent, AppConfigService cfg) {
        this.briefing = briefing;
        this.work = work;
        this.agent = agent;
        this.cfg = cfg;
    }

    /** Minute tick: fire the digest once, at the configured local time, outside quiet hours. */
    @Scheduled(fixedRate = 60_000)
    public void digestTick() {
        NotifyConfig c = NotifyConfig.from(cfg);
        if (!c.enabled()) return;
        LocalDateTime now = LocalDateTime.now(zone);
        if (!now.toLocalTime().truncatedTo(ChronoUnit.MINUTES).equals(parse(c.digestTime()))) return;
        if (now.toLocalDate().equals(lastDigestDate)) return;
        if (quiet(now.toLocalTime(), c)) return;
        lastDigestDate = now.toLocalDate();
        send("DevLoom · morning briefing", composeDigest(c), "normal");
        briefing.writeSnapshot("digest"); // establish today's "yesterday" baseline
    }

    /** Called after each sync: alert on newly-urgent items (unless quiet hours). */
    public void onSync() {
        NotifyConfig c = NotifyConfig.from(cfg);
        Map<String, UrgencyRules.SnapItem> prev = briefing.lastSnapshot("sync");
        List<WorkItemEntity> newlyUrgent = new ArrayList<>();
        for (WorkItemEntity w : work.findAll()) {
            String key = UrgencyRules.urgencyKey(w, c.urgentCi(), c.urgentReview(), c.prWaitHours());
            if (key == null) continue;
            UrgencyRules.SnapItem before = prev.get(w.getExtId());
            if (before == null || before.urgencyKey() == null) newlyUrgent.add(w);
        }
        briefing.writeSnapshot("sync");
        if (newlyUrgent.isEmpty() || !c.enabled()) return;
        if (quiet(LocalTime.now(zone), c)) return; // overnight items surface in the morning digest
        send("DevLoom · needs you", composeUrgent(newlyUrgent), "urgent");
    }

    private void send(String title, String body, String urgency) {
        try {
            Map<String, Object> r = agent.notify(title, body, urgency);
            if (!Boolean.TRUE.equals(r.get("ok"))) log.info("notify skipped: {}", r.get("error"));
        } catch (Exception e) {
            log.info("notify unavailable (agent down?): {}", e.getMessage());
        }
    }

    private String composeDigest(NotifyConfig c) {
        long urgent = work.findAll().stream()
                .filter(w -> UrgencyRules.urgencyKey(w, c.urgentCi(), c.urgentReview(), c.prWaitHours()) != null)
                .count();
        long planned = briefing.plannedExtIds().size();
        return "%d need you now · %d in today's plan. Open DevLoom for your briefing.".formatted(urgent, planned);
    }

    private String composeUrgent(List<WorkItemEntity> items) {
        if (items.size() == 1) return items.get(0).getTitle();
        String heads = items.stream().limit(3).map(WorkItemEntity::getTitle).collect(Collectors.joining(" · "));
        return items.size() + " things need you: " + heads + (items.size() > 3 ? " …" : "");
    }

    /** True if {@code now} is within the quiet window (which may cross midnight). */
    private boolean quiet(LocalTime now, NotifyConfig c) {
        LocalTime s = parse(c.quietStart()), e = parse(c.quietEnd());
        if (s.equals(e)) return false;
        return s.isBefore(e) ? (!now.isBefore(s) && now.isBefore(e))
                             : (!now.isBefore(s) || now.isBefore(e));
    }

    private static LocalTime parse(String hhmm) {
        try {
            return LocalTime.parse(hhmm).truncatedTo(ChronoUnit.MINUTES);
        } catch (Exception ex) {
            return LocalTime.of(8, 30);
        }
    }
}
