package racehorse.backend.preventive;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import racehorse.backend.notification.Notice;
import racehorse.backend.notification.NotificationService;
import racehorse.backend.preventive.PreventiveCareRepository.PreventiveCareRow;

/**
 * Tác vụ nhắc lịch chăm sóc phòng ngừa tự động, chạy hằng ngày.
 *
 * Mỗi sáng, quét toàn bộ bảng preventive care. Nếu có ô nào OVERDUE,
 * DUE_SOON (trong 14 ngày tới) hoặc NEVER (ngựa ở học viện lâu hơn 30 ngày
 * mà chưa từng được làm), gửi MỘT thông báo tổng hợp cho tất cả Vet.
 *
 * Không gửi từng thông báo cho từng con ngựa × từng loại: Vet mở chuông ra
 * thấy 300 dòng giống hệt nhau thì lần sau sẽ tắt chuông. Một dòng duy nhất
 * "12 quá hạn, 8 sắp đến hạn, 5 chưa từng làm — xem chi tiết" vừa đủ để
 * nhắc mà không làm phiền.
 *
 * Chống spam: nếu hôm nay đã gửi thì không gửi lại. Dùng query đếm
 * NOTIFICATION trong ngày có notif_type = 'PREVENTIVE_REMINDER'. Khi server
 * khởi động lại giữa ngày thì scheduled task có thể chạy lại; điều kiện này
 * ngăn gửi thông báo trùng.
 */
@Component
public class PreventiveCareScheduler {

    private static final Logger log = LoggerFactory.getLogger(PreventiveCareScheduler.class);

    /** Ngựa ở học viện bao nhiêu ngày mà chưa từng làm thì đáng nhắc. */
    private static final int NEVER_THRESHOLD_DAYS = 30;

    /** Bao nhiêu ngày trước hạn thì tính là DUE_SOON. */
    private static final int DUE_SOON_WINDOW = 14;

    private static final String NOTIF_TYPE = "PREVENTIVE_REMINDER";

    private final PreventiveCareRepository careRepo;
    private final NotificationService notifications;
    private final PreventiveCareSchedulerRepository schedulerRepo;

    public PreventiveCareScheduler(PreventiveCareRepository careRepo,
                                   NotificationService notifications,
                                   PreventiveCareSchedulerRepository schedulerRepo) {
        this.careRepo = careRepo;
        this.notifications = notifications;
        this.schedulerRepo = schedulerRepo;
    }

    /**
     * Chạy mỗi ngày lúc 07:00 (giờ server).
     *
     * Cron 6 trường của Spring: giây phút giờ ngày tháng thứ.
     */
    @Scheduled(cron = "0 0 7 * * *")
    public void remindPreventiveCare() {
        log.info("Preventive care reminder: bắt đầu quét...");

        /* Chống spam: đã gửi hôm nay thì thôi. */
        if (schedulerRepo.hasReminderToday(NOTIF_TYPE)) {
            log.info("Preventive care reminder: đã gửi hôm nay, bỏ qua.");
            return;
        }

        LocalDate today = LocalDate.now();
        List<PreventiveCareRow> all = careRepo.findAll();

        int overdue = 0;
        int dueSoon = 0;
        int never = 0;

        for (PreventiveCareRow row : all) {
            if (row.lastRecordId() == null) {
                /* Chưa từng làm: chỉ nhắc khi ngựa đã ở đủ lâu. */
                if (row.horseCreatedAt() != null) {
                    long daysSinceReg = ChronoUnit.DAYS.between(
                            row.horseCreatedAt().toLocalDate(), today);
                    if (daysSinceReg >= NEVER_THRESHOLD_DAYS) {
                        never++;
                    }
                }
            } else if (row.nextDueDate() != null) {
                long daysUntil = ChronoUnit.DAYS.between(today, row.nextDueDate());
                if (daysUntil < 0) {
                    overdue++;
                } else if (daysUntil <= DUE_SOON_WINDOW) {
                    dueSoon++;
                }
            }
        }

        int total = overdue + dueSoon + never;
        if (total == 0) {
            log.info("Preventive care reminder: không có việc cần nhắc.");
            return;
        }

        String title = "Nhắc lịch chăm sóc phòng ngừa";
        StringBuilder msg = new StringBuilder();
        if (overdue > 0) {
            msg.append(overdue).append(" quá hạn");
        }
        if (dueSoon > 0) {
            if (!msg.isEmpty()) msg.append(", ");
            msg.append(dueSoon).append(" sắp đến hạn");
        }
        if (never > 0) {
            if (!msg.isEmpty()) msg.append(", ");
            msg.append(never).append(" chưa từng thực hiện");
        }
        msg.append(" — xem chi tiết trong Preventive Care.");

        Notice notice = new Notice(
                NOTIF_TYPE,
                title,
                msg.toString(),
                Notice.PREVENTIVE_CARE,
                null);

        notifications.notifyRole("VETERINARIAN", null, notice);

        log.info("Preventive care reminder: đã gửi ({} quá hạn, {} sắp hạn, {} chưa làm).",
                overdue, dueSoon, never);
    }
}
