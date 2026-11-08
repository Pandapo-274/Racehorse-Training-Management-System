package racehorse.backend.care;

import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import racehorse.backend.session.SessionService;
import racehorse.backend.training.TrainingPlanService;
import racehorse.backend.training.TrainingSessionRepository;
import racehorse.backend.training.TrainingSessionService;

/**
 * Scheduled background jobs for care tasks and training session automation.
 */
@Component
public class MissedTaskJob {

    private static final Logger log = LoggerFactory.getLogger(MissedTaskJob.class);

    private final CareService care;
    private final TrainingPlanService plans;
    private final TrainingSessionRepository sessions;
    private final TrainingSessionService trainingSessionService;
    private final SessionService sessionService;
    private final boolean enabled;

    public MissedTaskJob(CareService care, TrainingPlanService plans, TrainingSessionRepository sessions,
                         TrainingSessionService trainingSessionService, SessionService sessionService,
                         @Value("${app.task.missed.enabled:true}") boolean enabled) {
        this.care = care;
        this.plans = plans;
        this.sessions = sessions;
        this.trainingSessionService = trainingSessionService;
        this.sessionService = sessionService;
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
        try {
            LocalDate today = LocalDate.now();
            log.info("Expired training plans completed: {}, overdue sessions cancelled: {}, "
                            + "stale running sessions closed: {}",
                    plans.completeExpiredPlans(), sessions.cancelOverdue(today),
                    sessions.closeStaleRunning(today));
        } catch (RuntimeException e) {
            log.error("Training clean-up failed", e);
        }
    }

    /**
     * V37: Quét định kỳ mỗi phút để:
     * 1. Tự động hoàn tất (COMPLETED) các buổi tập RUNNING đã hết thời lượng và gửi về Head Trainer.
     * 2. Tự động huỷ (CANCELLED) các buổi SCHEDULED đã qua khung giờ.
     * 3. Tự động tạo báo cáo sơ bộ + chuyển vào khu lưu trữ (ARCHIVED_AUTO) cho các buổi quá hạn review 48h.
     */
    @Scheduled(fixedDelayString = "${app.training.auto-check-ms:60000}")
    public void runPeriodicTrainingChecks() {
        if (!enabled) {
            return;
        }
        try {
            trainingSessionService.processOverdueSessions();
            sessionService.processOverdueReviews();
        } catch (RuntimeException e) {
            log.error("Periodic training check failed", e);
        }
    }
}
