package racehorse.backend.care;

import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import racehorse.backend.training.TrainingPlanService;
import racehorse.backend.training.TrainingSessionRepository;

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
    private final TrainingPlanService plans;
    private final TrainingSessionRepository sessions;
    private final boolean enabled;

    public MissedTaskJob(CareService care, TrainingPlanService plans, TrainingSessionRepository sessions,
                         @Value("${app.task.missed.enabled:true}") boolean enabled) {
        this.care = care;
        this.plans = plans;
        this.sessions = sessions;
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
        /* Cùng một lần chạy đêm: kết thúc giáo án đã qua ngày kết thúc và huỷ
           buổi tập đã lỡ ngày. Tách try để lỗi phần này không chặn phần kia. */
        try {
            log.info("Expired training plans completed: {}, overdue sessions cancelled: {}",
                    plans.completeExpiredPlans(), sessions.cancelOverdue(LocalDate.now()));
        } catch (RuntimeException e) {
            log.error("Training clean-up failed", e);
        }
    }
}
