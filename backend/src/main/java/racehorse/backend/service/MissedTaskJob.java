package racehorse.backend.service;

import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import racehorse.backend.repository.TrainingSessionRepository;

/**
 * Scheduled background jobs for care tasks and training session automation.
 */
/*
 * Hai job chạy nền (cần @EnableScheduling ở BackendApplication):
 *   runDaily (00:15 mỗi đêm, giờ Việt Nam - cron "0 15 0 * * *" = giây phút giờ ngày tháng thứ):
 *     - đánh dấu việc chăm sóc hôm qua chưa làm là MISSED (CareService.stampMissed);
 *     - kết thúc giáo án ACTIVE/PAUSED đã quá end_date;
 *     - huỷ buổi SCHEDULED của ngày đã qua; đóng buổi RUNNING bị bỏ quên từ hôm trước.
 *   runPeriodicTrainingChecks (mỗi 60 giây, fixedDelay = chờ 60s sau khi lần trước chạy xong):
 *     - tự kết thúc buổi RUNNING hết giờ, tự huỷ buổi SCHEDULED quá khung giờ;
 *     - lưu trữ tự động các buổi quá hạn nhận xét 48 giờ;
 *     - tự từ chối bản sửa giáo án chờ chủ duyệt quá 2 ngày.
 * Mỗi khối có try/catch riêng: một việc lỗi không chặn các việc còn lại, và lỗi chỉ ghi log.
 * app.task.missed.enabled=false để tắt cả hai (vd. khi chạy test).
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
            plans.expireOverduePendingUpdates();
        } catch (RuntimeException e) {
            log.error("Periodic training check failed", e);
        }
    }
}
