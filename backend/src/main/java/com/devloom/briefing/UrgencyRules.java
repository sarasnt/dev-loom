package com.devloom.briefing;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.devloom.workmodel.WorkItemEntity;

/**
 * Deterministic "is this urgent, and why" for the briefing (spec §7). Urgent = a new CI failure,
 * a review requested of you, or a PR waiting on you past a threshold. Everything else is digest
 * material, not an interrupt.
 */
public final class UrgencyRules {

    private UrgencyRules() {}

    /** Minimal projection of a work item stored in a snapshot for diffing. */
    public record SnapItem(String extId, String type, String source, String status, String urgencyKey) {}

    private static final Pattern WAIT = Pattern.compile("wait[:=]\\s*(\\d+)\\s*h", Pattern.CASE_INSENSITIVE);

    /** Default thresholds (Slice 1 callers). */
    public static String urgencyKey(WorkItemEntity w) {
        return urgencyKey(w, true, true, 24);
    }

    public static String urgencyKey(WorkItemEntity w, boolean ci, boolean review, int prWaitHours) {
        String type = w.getType() == null ? "" : w.getType();
        String tone = w.getStatusTone() == null ? "" : w.getStatusTone();
        if (ci && "build".equals(type) && "fail".equals(tone)) return "ci-fail";
        if (review && ("review".equals(type) || (isPr(type) && isReviewRequested(w)))) return "review-req";
        if (isPr(type) && waitHours(w) >= prWaitHours) return "pr-wait";
        return null;
    }

    private static boolean isPr(String type) {
        return "pr".equals(type);
    }

    private static boolean isReviewRequested(WorkItemEntity w) {
        String hay = ((w.getMetaCsv() == null ? "" : w.getMetaCsv()) + " "
                + (w.getStatus() == null ? "" : w.getStatus())).toLowerCase();
        return hay.contains("review");
    }

    /**
     * Wait age in hours, read from a {@code wait:<n>h} token in the item's meta (the same token the
     * Today chips render). Absent → 0, so the pr-wait rule is inert until a wait signal is present.
     */
    private static long waitHours(WorkItemEntity w) {
        String meta = w.getMetaCsv();
        if (meta == null || meta.isBlank()) return 0;
        Matcher m = WAIT.matcher(meta);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }
}
