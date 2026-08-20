package com.devloom.integrations;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.devloom.workmodel.WorkItemEntity;

/**
 * Calendar via a private iCal (ICS) feed (docs/SPEC-sources.md). Each instance supplies its
 * own feed URL (a secret). Events are classified upcoming → ongoing → ended by the clock.
 */
@Component
public class CalendarIcsConnector implements SourceConnector {

    private static final Logger log = LoggerFactory.getLogger(CalendarIcsConnector.class);
    private static final DateTimeFormatter DISP_DATETIME = DateTimeFormatter.ofPattern("EEE MMM d HH:mm");
    private static final DateTimeFormatter DISP_DATE = DateTimeFormatter.ofPattern("EEE MMM d");
    /** An event this close to starting is treated like one already running. */
    private static final int STARTING_SOON_MINUTES = 30;

    private static final DateTimeFormatter DISP_TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final int maxEvents;
    private final int horizonDays;

    public CalendarIcsConnector(
            @Value("${devloom.calendar.max-events:15}") int maxEvents,
            @Value("${devloom.calendar.horizon-days:28}") int horizonDays) {
        this.maxEvents = maxEvents;
        this.horizonDays = horizonDays;
    }

    @Override
    public String type() {
        return "calendar";
    }

    @Override
    public SetupDescriptor.Type describe() {
        return new SetupDescriptor.Type("calendar", "Calendar", List.of(
                new SetupDescriptor.Deployment("ics", "iCal / ICS feed", List.of(
                        SetupDescriptor.Field.secret("icsUrl", "Private ICS URL", "https://…/basic.ics")))));
    }

    @Override
    public List<WorkItemEntity> fetch(SourceInstanceEntity inst, Map<String, String> secrets) {
        String icsUrl = secrets.getOrDefault("icsUrl", "");
        String source = inst.getName();
        RestClient http = RestClient.builder().build();
        try {
            // Concrete URI so RestClient doesn't re-encode the already-encoded URL.
            String ics = http.get().uri(URI.create(icsUrl)).retrieve().body(String.class);
            if (ics == null || ics.isBlank()) {
                throw new IllegalStateException("empty ICS feed");
            }
            List<Event> events = parse(unfold(ics));
            LocalDateTime now = LocalDateTime.now(ZONE);
            LocalDateTime lookback = now.minusDays(1);
            LocalDateTime until = now.plusDays(horizonDays);

            List<WorkItemEntity> out = new ArrayList<>();
            int order = 50;
            List<Event> window = events.stream()
                    .filter(e -> e.start != null)
                    .filter(e -> endOf(e).isAfter(lookback) && e.start.isBefore(until))
                    .sorted(Comparator.comparing(e -> e.start))
                    .limit(maxEvents)
                    .toList();
            for (Event e : window) {
                LocalDateTime end = endOf(e);
                String when = e.allDay ? e.start.toLocalDate().format(DISP_DATE) : e.start.format(DISP_DATETIME);
                String tone;
                String status;
                if (end.isBefore(now) || end.isEqual(now)) {
                    tone = "healthy";
                    status = "ended · " + when;
                } else if (!e.start.isAfter(now)) {
                    tone = "warn";
                    status = e.allDay ? "today" : "now · until " + end.format(DISP_TIME);
                } else if (!e.allDay && e.start.isBefore(now.plusMinutes(STARTING_SOON_MINUTES))) {
                    // A meeting about to start is time-critical in a way an open PR isn't, and
                    // graded only as "upcoming" it ranked below every one of them. Upcoming was a
                    // single band covering both "in ten minutes" and "in three weeks".
                    tone = "warn";
                    status = "starts " + e.start.format(DISP_TIME);
                } else {
                    tone = "info";
                    status = when;
                }
                String extId = (e.uid != null && !e.uid.isBlank() ? e.uid : e.summary + "@" + e.start);
                if (extId.length() > 60) extId = extId.substring(0, 60);
                out.add(WorkItemEntity.create(extId, "calendar",
                        e.summary == null || e.summary.isBlank() ? "(untitled event)" : e.summary,
                        status, tone, "", source, order++)
                        // e.start is a naive LocalDateTime already expressed in ZONE (all-day
                        // events land at local midnight via LocalDate.atStartOfDay()), so this
                        // is the same clock the display strings above are computed from.
                        .withStartsAt(e.start.atZone(ZONE).toInstant()));
            }
            log.info("Calendar sync [{}]: {} events in window (of {} parsed)", source, out.size(), events.size());
            return out;
        } catch (Exception e) {
            log.warn("Calendar sync failed [{}]: {}", source, e.getMessage());
            throw new IllegalStateException("Calendar fetch failed", e);
        }
    }

    // ---- ICS parsing ----
    private record Event(String uid, String summary, LocalDateTime start, LocalDateTime end, boolean allDay) {}

    private static LocalDateTime endOf(Event e) {
        if (e.end != null) return e.end;
        if (e.allDay) return e.start.toLocalDate().plusDays(1).atStartOfDay();
        return e.start.plusHours(1);
    }

    private static String unfold(String ics) {
        return ics.replace("\r\n", "\n").replaceAll("\n[ \t]", "");
    }

    private List<Event> parse(String ics) {
        List<Event> events = new ArrayList<>();
        String[] blocks = ics.split("BEGIN:VEVENT");
        for (int i = 1; i < blocks.length; i++) {
            String block = blocks[i];
            String summary = value(block, "SUMMARY");
            String uid = value(block, "UID");
            String dtstartLine = line(block, "DTSTART");
            String dtendLine = line(block, "DTEND");
            LocalDateTime start = null, end = null;
            boolean allDay = false;
            if (dtstartLine != null) {
                allDay = dtstartLine.toUpperCase().contains("VALUE=DATE") && !dtstartLine.contains("T");
                start = parseDate(rawOf(dtstartLine), tzidOf(dtstartLine));
                if (start == null) {
                    allDay = false;
                }
            }
            if (dtendLine != null) {
                end = parseDate(rawOf(dtendLine), tzidOf(dtendLine));
            }
            events.add(new Event(uid, summary, start, end, allDay));
        }
        return events;
    }

    private static String rawOf(String propLine) {
        return propLine.substring(propLine.lastIndexOf(':') + 1).trim();
    }

    private static String tzidOf(String propLine) {
        int i = propLine.toUpperCase().indexOf("TZID=");
        if (i < 0) return null;
        String rest = propLine.substring(i + 5);
        int end = rest.indexOf(':');
        int semi = rest.indexOf(';');
        int cut = (semi >= 0 && semi < end) ? semi : end;
        return cut > 0 ? rest.substring(0, cut).trim() : null;
    }

    private static String line(String block, String prop) {
        for (String l : block.split("\n")) {
            String t = l.trim();
            if (t.startsWith(prop + ":") || t.startsWith(prop + ";")) {
                return t;
            }
        }
        return null;
    }

    private static String value(String block, String prop) {
        String l = line(block, prop);
        if (l == null) return null;
        int c = l.indexOf(':');
        return c >= 0 ? l.substring(c + 1).trim() : null;
    }

    private static LocalDateTime parseDate(String raw, String tzid) {
        try {
            boolean utc = raw.endsWith("Z");
            String d = raw.replace("Z", "");
            if (d.length() >= 15 && d.charAt(8) == 'T') {
                int y = Integer.parseInt(d.substring(0, 4));
                int mo = Integer.parseInt(d.substring(4, 6));
                int da = Integer.parseInt(d.substring(6, 8));
                int h = Integer.parseInt(d.substring(9, 11));
                int mi = Integer.parseInt(d.substring(11, 13));
                LocalDateTime naive = LocalDateTime.of(y, mo, da, h, mi);
                ZoneId src = utc ? ZoneOffset.UTC : zoneOrNull(tzid);
                return src == null ? naive
                        : naive.atZone(src).withZoneSameInstant(ZONE).toLocalDateTime();
            }
            if (d.length() >= 8) {
                int y = Integer.parseInt(d.substring(0, 4));
                int mo = Integer.parseInt(d.substring(4, 6));
                int da = Integer.parseInt(d.substring(6, 8));
                return LocalDate.of(y, mo, da).atStartOfDay();
            }
        } catch (RuntimeException ignore) {
            // fall through
        }
        return null;
    }

    private static ZoneId zoneOrNull(String tzid) {
        if (tzid == null || tzid.isBlank()) return null;
        try {
            return ZoneId.of(tzid);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
