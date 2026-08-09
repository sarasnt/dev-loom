package com.devloom.integrations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Seeds source instances from {@code .env} on first boot (docs/SPEC-sources.md §11) so the
 * upgrade is non-breaking: the previously env-configured Jira/GitHub/Calendar/Notion become
 * named instances. No credential rows are stored — {@link SourceCredentialStore} falls back to
 * the {@code .env} values by type, so this works without {@code DEVLOOM_SECRET}. Runs before
 * {@link StartupSync}.
 */
@Component
@Order(1)
public class SourceSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SourceSeeder.class);

    private final SourceInstanceRepository instances;
    private final String jiraBaseUrl;
    private final String jiraPat;
    private final String githubToken;
    private final String calendarIcs;
    private final String notionToken;

    public SourceSeeder(SourceInstanceRepository instances,
                        @Value("${devloom.jira.base-url:}") String jiraBaseUrl,
                        @Value("${devloom.jira.pat:}") String jiraPat,
                        @Value("${devloom.github.token:}") String githubToken,
                        @Value("${devloom.calendar.ics-url:}") String calendarIcs,
                        @Value("${devloom.notion.token:}") String notionToken) {
        this.instances = instances;
        this.jiraBaseUrl = jiraBaseUrl;
        this.jiraPat = jiraPat;
        this.githubToken = githubToken;
        this.calendarIcs = calendarIcs;
        this.notionToken = notionToken;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (instances.count() > 0) {
            return; // already configured
        }
        int n = 0;
        if (present(jiraPat) && present(jiraBaseUrl)) {
            instances.save(SourceInstanceEntity.of("jira", "onprem", "Jira", jiraBaseUrl.strip(), null));
            n++;
        }
        if (present(githubToken)) {
            instances.save(SourceInstanceEntity.of("github", "cloud", "GitHub", null, null));
            n++;
        }
        if (present(calendarIcs)) {
            instances.save(SourceInstanceEntity.of("calendar", "ics", "Calendar", null, null));
            n++;
        }
        if (present(notionToken)) {
            instances.save(SourceInstanceEntity.of("notion", "cloud", "Notion", null, null));
            n++;
        }
        if (n > 0) {
            log.info("Seeded {} source instances from .env", n);
        }
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }
}
