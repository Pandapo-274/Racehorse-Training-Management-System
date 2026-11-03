package racehorse.backend.care;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Each morning, stamps DAILY_TASK.missed_at (V25) on every task of an earlier
 * day that was never ticked, and tells the Head Trainers how many there were.
 *
 * The MISSED state shown on screen does not depend on this job - it is derived
 * from the date (CareRepository.stateOf) - so a night the server was off loses
 * nothing but the timestamp. The job exists for the record and the bell.
 *
 *   app.task.missed.enabled  (default true)
 *   app.task.missed.cron     (default 00:15 every day)
 *   app.task.missed.zone     (default Asia/Ho_Chi_Minh)
 */
@Component
public class MissedTaskJob {

    private static final Logger log = LoggerFactory.getLogger(MissedTaskJob.class);

    private final CareService care;
    private final boolean enabled;

    public MissedTaskJob(CareService care, @Value("${app.task.missed.enabled:true}") boolean enabled) {
        this.care = care;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${app.task.missed.cron:0 15 0 * * *}", zone = "${app.task.missed.zone:Asia/Ho_Chi_Minh}")
    public void runDaily() {
        if (!enabled) {
            return;
        }
        try {
            log.info("Missed care tasks stamped: {}", care.stampMissed());
        } catch (RuntimeException e) {
            log.error("Missed task job failed", e);
        }
    }
}
