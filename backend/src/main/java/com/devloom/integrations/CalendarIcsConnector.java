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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.devloom.workmodel.WorkItemEntity;

/**
 * Google Calendar via a private iCal (ICS) feed (SPEC.md §12/§13). Token-free, read-only —
 * ideal for a local-first tool. Fetches the feed, parses VEVENTs, and surfaces upcoming
 * events within a horizon as {@link WorkItemEntity}. Disabled without a URL.
 *
 * <p>Minimal hand-rolled ICS parse (no heavy iCal4j dep): unfold folded lines, split on
 * VEVENT, read SUMMARY / DTSTART / UID. Recurring-event expansion is out of scope for the
 * MVP — a VEVENT is shown by its DTSTART occurrence if it falls in the horizon.
 */
@Component
public class CalendarIcsConnector implements SourceConnector {

    private static final Logger log = LoggerFactory.getLogger(CalendarIcsConnector.class);
    private static final DateTimeFormatter DISP_DATETIME = DateTimeFormatter.ofPattern("EEE MMM d HH:mm");
    private static final DateTimeFormatter DISP_DATE = DateTimeFormatter.ofPattern("EEE MMM d");
    private static final DateTimeFormatter DISP_TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final String icsUrl;
    private final int maxEvents;
    private final int horizonDays;
    private final RestClient http;

    public CalendarIcsConnector(
            @Value("${devloom.calendar.ics-url:}") String icsUrl,
            @Value("${devloom.calendar.max-events:15}") int maxEvents,
            @Value("${devloom.calendar.horizon-days:28}") int horizonDays) {
        this.icsUrl = icsUrl;
        this.maxEvents = maxEvents;
        this.horizonDays = horizonDays;
        this.http = RestClient.builder().build();
    }

    @Override
    public String source() {
        return "Calendar";
    }

    @Override
    public boolean enabled() {
        return icsUrl != null && !icsUrl.isBlank();
    }

    @Override
    public List<WorkItemEntity> fetch() {
        if (!enabled()) {
            return List.of();
        }
        try {
            // Pass a concrete URI so RestClient does NOT re-encode the already-encoded
            // URL (the %40 in the address would otherwise be double-encoded → 404).
            String ics = http.get().uri(URI.create(icsUrl)).retrieve().body(String.class);
            if (ics == null || ics.isBlank()) {
                throw new IllegalStateException("empty ICS feed");
            }
            List<Event> events = parse(unfold(ics));
            LocalDateTime now = LocalDateTime.now(ZONE);
            LocalDateTime lookback = now.minusDays(1);   // keep just-ended events (shown under "All")
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

                // Open/ongoing is time-based: upcoming (info) → ongoing (warn) → ended (healthy=done).
                String tone;
                String status;
                if (end.isBefore(now) || end.isEqual(now)) {
                    tone = "healthy";
                    status = "ended · " + when;
                } else if (!e.start.isAfter(now)) {
                    tone = "warn";
                    status = e.allDay ? "today" : "now · until " + end.format(DISP_TIME);
                } else {
                    tone = "info";
                    status = when;
                }

                String extId = (e.uid != null && !e.uid.isBlank() ? e.uid : e.summary + "@" + e.start);
                if (extId.length() > 60) extId = extId.substring(0, 60);
                out.add(WorkItemEntity.create(extId, "calendar",
                        e.summary == null || e.summary.isBlank() ? "(untitled event)" : e.summary,
                        status, tone, "Google", source(), order++));
            }
            log.info("Calendar sync: {} events in window (of {} parsed)", out.size(), events.size());
            return out;
        } catch (Exception e) {
            log.warn("Calendar sync failed: {}", e.getMessage());
            throw new IllegalStateException("Calendar fetch failed", e);
        }
    }

    // ---- ICS parsing ----
    private record Event(String uid, String summary, LocalDateTime start, LocalDateTime end, boolean allDay) {}

    /** Effective end: explicit DTEND, else end-of-day for all-day, else start + 1h. */
    private static LocalDateTime endOf(Event e) {
        if (e.end != null) return e.end;
        if (e.allDay) return e.start.toLocalDate().plusDays(1).atStartOfDay();
        return e.start.plusHours(1);
    }

    private static String unfold(String ics) {
        // RFC 5545 line folding: a CRLF followed by space/tab continues the previous line.
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

    /** The value after the last ':' on a property line. */
    private static String rawOf(String propLine) {
        return propLine.substring(propLine.lastIndexOf(':') + 1).trim();
    }

    /** The TZID=... parameter on a property line, if present. */
    private static String tzidOf(String propLine) {
        int i = propLine.toUpperCase().indexOf("TZID=");
        if (i < 0) return null;
        String rest = propLine.substring(i + 5);
        int end = rest.indexOf(':');
        int semi = rest.indexOf(';');
        int cut = (semi >= 0 && semi < end) ? semi : end;
        return cut > 0 ? rest.substring(0, cut).trim() : null;
    }

    /** Line beginning with the property name (possibly with params before ':'). */
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

    /**
     * Parse yyyyMMdd or yyyyMMdd'T'HHmmss['Z'] into a wall-clock time in the app zone.
     * A trailing 'Z' means UTC; a TZID names the source zone; otherwise the time is floating
     * and taken as-is. Returns null if unparseable.
     */
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
