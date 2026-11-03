package racehorse.backend.supply;

import java.time.LocalDate;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily store check (#12): repeats the low-stock warning every morning while an
 * item is still below its minimum, and warns about medicine that has expired or
 * expires within 30 days.
 *
 * Configuration (application.properties, all optional):
 *   app.supply.digest.enabled   true/false (default true)
 *   app.supply.digest.cron      6-field cron (default 07:05 every day, after the
 *                               preventive-care reminders at 07:00)
 *   app.supply.digest.zone      time zone of the cron (default Asia/Ho_Chi_Minh)
 *
 * @EnableScheduling is already on PreventiveReminderJob, which is enough for
 * the whole application context.
 */
@Component
public class SupplyDigestJob {

    private static final Logger log = LoggerFactory.getLogger(SupplyDigestJob.class);

    private final SupplyService service;
    private final boolean enabled;
    private final ZoneId zone;

    public SupplyDigestJob(SupplyService service,
                           @Value("${app.supply.digest.enabled:true}") boolean enabled,
                           @Value("${app.supply.digest.zone:Asia/Ho_Chi_Minh}") String zone) {
        this.service = service;
        this.enabled = enabled;
        this.zone = ZoneId.of(zone);
    }

    @Scheduled(cron = "${app.supply.digest.cron:0 5 7 * * *}",
               zone = "${app.supply.digest.zone:Asia/Ho_Chi_Minh}")
    public void runDaily() {
        if (!enabled) {
            return;
        }
        try {
            int n = service.sendDailyDigest(LocalDate.now(zone));
            log.info("Store check: {} item(s) in today's digest", n);
        } catch (RuntimeException e) {
            log.error("Store check digest failed", e);
        }
    }
}
