package com.devloom.briefing;

import com.devloom.common.AppConfigService;

/** Desktop-notification settings, read from {@code app_config} with the spec §14 defaults. */
public record NotifyConfig(boolean enabled, String digestTime, String quietStart, String quietEnd,
                           boolean urgentCi, boolean urgentReview, int prWaitHours) {

    public static NotifyConfig from(AppConfigService cfg) {
        return new NotifyConfig(
                cfg.get(AppConfigService.NOTIFY_ENABLED).map(Boolean::parseBoolean).orElse(false),
                cfg.get(AppConfigService.NOTIFY_DIGEST_TIME).orElse("08:30"),
                cfg.get(AppConfigService.NOTIFY_QUIET_START).orElse("22:00"),
                cfg.get(AppConfigService.NOTIFY_QUIET_END).orElse("08:00"),
                cfg.get(AppConfigService.NOTIFY_URGENT_CI).map(Boolean::parseBoolean).orElse(true),
                cfg.get(AppConfigService.NOTIFY_URGENT_REVIEW).map(Boolean::parseBoolean).orElse(true),
                cfg.get(AppConfigService.NOTIFY_PR_WAIT_HOURS).map(Integer::parseInt).orElse(24));
    }

    /** As a JSON-friendly map for the settings endpoint. */
    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("digestTime", digestTime);
        m.put("quietStart", quietStart);
        m.put("quietEnd", quietEnd);
        m.put("urgentCi", urgentCi);
        m.put("urgentReview", urgentReview);
        m.put("prWaitHours", prWaitHours);
        return m;
    }
}
