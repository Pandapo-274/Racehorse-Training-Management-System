package racehorse.backend.preventive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tác vụ chạy mỗi sáng để nhắc lịch phòng ngừa.
 *
 * Project chưa có tác vụ định kỳ nào, nên @EnableScheduling đặt luôn ở đây thay vì
 * thêm một lớp cấu hình riêng chỉ để chứa một dòng.
 *
 * Cấu hình trong application.properties (đều có mặc định):
 *   app.preventive.reminder.enabled  true để chạy (mặc định true)
 *   app.preventive.reminder.cron     biểu thức cron 6 trường (mặc định 07:00 mỗi ngày)
 *   app.preventive.reminder.zone     múi giờ của cron (mặc định Asia/Ho_Chi_Minh)
 *
 * 07:00 giờ Việt Nam là 00:00 UTC cùng ngày dương lịch, nên dù máy chủ đặt UTC hay
 * giờ Việt Nam thì LocalDate.now() trong service vẫn ra cùng một ngày. Đổi giờ chạy
 * ra khỏi khoảng này thì phải xem lại điều đó.
 *
 * Nếu máy tắt đúng lúc 07:00 thì hôm đó bỏ lỡ; nút chạy tay (POST
 * /api/preventive-care/reminders/run) là đường bù, và vì service idempotent nên bấm
 * thừa cũng không gửi trùng.
 */
@Component
@EnableScheduling
public class PreventiveReminderJob {

    private static final Logger log = LoggerFactory.getLogger(PreventiveReminderJob.class);

    private final PreventiveReminderService service;
    private final boolean enabled;

    public PreventiveReminderJob(PreventiveReminderService service,
                                 @Value("${app.preventive.reminder.enabled:true}") boolean enabled) {
        this.service = service;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${app.preventive.reminder.cron:0 0 7 * * *}",
               zone = "${app.preventive.reminder.zone:Asia/Ho_Chi_Minh}")
    public void runDaily() {
        if (!enabled) {
            return;
        }
        /* Bắt hết: ngoại lệ lọt ra khỏi một @Scheduled chỉ được ghi log chung chung, và
           tệ hơn là dễ bị nhầm với lỗi của việc khác. Tự bắt để log nói rõ việc nào hỏng. */
        try {
            PreventiveReminderResult r = service.run(null, "scheduler");
            log.info("Preventive care reminders: {} cells reminded ({} overdue, {} due soon, "
                            + "{} never), {} skipped, {} vets notified",
                    r.reminded(), r.overdue(), r.dueSoon(), r.never(), r.skipped(), r.vetsNotified());
        } catch (RuntimeException e) {
            log.error("Preventive care reminder run failed", e);
        }
    }
}
