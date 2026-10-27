package racehorse.backend.preventive;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import racehorse.backend.auth.UserRepository;
import racehorse.backend.notification.Notice;
import racehorse.backend.notification.NotificationService;
import racehorse.backend.preventive.PreventiveReminderLogRepository.ReminderKey;

/**
 * UC17 - nhắc tự động lịch tiêm phòng, tẩy giun, cắt móng.
 *
 * Đề bài ghi bác sĩ thú y "nhận thông báo tự động" - bảng UC17 chỉ trả lời khi có
 * người mở nó ra. Lớp này là phần chủ động: mỗi sáng quét bảng, thấy ô nào đáng
 * nhắc thì gửi MỘT thông báo tóm tắt cho các bác sĩ.
 *
 * KHÔNG tự tính lại "ô nào quá hạn": hỏi thẳng PreventiveCareService.board(). Có
 * một bộ phân loại duy nhất thì chuông và bảng không thể nói hai điều khác nhau.
 *
 * Nhắc những gì:
 *   OVERDUE, DUE_SOON, NEVER - có việc phải làm.
 *   UNSCHEDULED - KHÔNG nhắc: bản ghi không có ngày hẹn nên không biết nhắc "khi nào";
 *   nó vẫn hiện trên bảng UC17.
 *
 * Khi nào nhắc lại: xem PREVENTIVE_REMINDER_LOG (V14). DUE_SOON một lần; OVERDUE và
 * NEVER nhắc lại sau REPEAT_AFTER_DAYS nếu vẫn chưa ai làm.
 */
@Service
public class PreventiveReminderService {

    static final String NOTICE_TYPE = "PREVENTIVE_CARE_DUE";

    /** OVERDUE và NEVER mà vẫn chưa ai làm thì sau chừng này ngày nhắc lại. */
    static final int REPEAT_AFTER_DAYS = 7;

    /** Dòng sổ cũ hơn mốc này thì dọn. Dài hơn chu kỳ tiêm phòng một năm một chút. */
    static final int KEEP_LOG_DAYS = 400;

    private static final Set<String> REMIND_STATUSES = Set.of("OVERDUE", "DUE_SOON", "NEVER");

    private final PreventiveCareService care;
    private final PreventiveReminderLogRepository logs;
    private final NotificationService notifications;
    private final UserRepository users;

    public PreventiveReminderService(PreventiveCareService care,
                                     PreventiveReminderLogRepository logs,
                                     NotificationService notifications,
                                     UserRepository users) {
        this.care = care;
        this.logs = logs;
        this.notifications = notifications;
        this.users = users;
    }

    /**
     * Quét và nhắc một lần. Idempotent: chạy hai lần liên tiếp thì lần hai không gửi gì.
     *
     * @Transactional vì gửi thông báo và ghi sổ phải cùng thành hoặc cùng bại. Gửi mà
     * không ghi sổ thì sáng mai nhắc lại y hệt; ghi sổ mà không gửi thì ô đó im lặng
     * cho đến khi hết hạn nhắc lại - và đó là lỗi khó phát hiện hơn nhiều.
     *
     * @param actorId người bấm chạy tay, null nếu là tác vụ định kỳ (không ghi audit
     *                cho tác vụ định kỳ: mỗi ngày một dòng là nhiễu)
     * @param ip      nguồn gọi, ghi vào audit khi có actorId
     */
    @Transactional
    public PreventiveReminderResult run(Integer actorId, String ip) {
        LocalDate today = LocalDate.now();

        List<PreventiveCareItem> candidates = care
                .board(PreventiveCareService.DEFAULT_DUE_WITHIN_DAYS, false)
                .items().stream()
                .filter(i -> REMIND_STATUSES.contains(i.careStatus()))
                .toList();

        Map<ReminderKey, LocalDate> sent = logs.findAll();
        List<PreventiveCareItem> toRemind = candidates.stream()
                .filter(i -> shouldRemind(i, sent, today))
                .toList();

        int vetsNotified = 0;
        if (!toRemind.isEmpty()) {
            PreventiveReminderDigest.Summary digest = PreventiveReminderDigest
                    .build(toRemind, PreventiveCareService.DEFAULT_DUE_WITHIN_DAYS);
            vetsNotified = notifications.notifyRole("VETERINARIAN", null,
                    new Notice(NOTICE_TYPE, digest.title(), digest.message(), null, null));

            /* Chỉ ghi sổ khi có người thật sự nhận. Không có bác sĩ ACTIVE nào mà vẫn
               ghi "đã nhắc" thì khi tài khoản bác sĩ được mở lại, đống việc này vẫn im
               lặng suốt một tuần. */
            if (vetsNotified > 0) {
                for (PreventiveCareItem i : toRemind) {
                    logs.markSent(keyOf(i), today);
                }
            }
        }

        logs.deleteOlderThan(today.minusDays(KEEP_LOG_DAYS));

        if (actorId != null) {
            users.writeAudit(actorId, "PREVENTIVE_REMINDER_RUN", null, null, "SUCCESS", ip);
        }

        /* Đếm trên toRemind (cái thật sự đưa vào tin) kể cả khi không có ai nhận, để
           nút chạy tay còn nói được "có việc nhưng chưa có bác sĩ nào để báo". */
        return new PreventiveReminderResult(
                toRemind.size(),
                (int) toRemind.stream().filter(i -> "OVERDUE".equals(i.careStatus())).count(),
                (int) toRemind.stream().filter(i -> "DUE_SOON".equals(i.careStatus())).count(),
                (int) toRemind.stream().filter(i -> "NEVER".equals(i.careStatus())).count(),
                candidates.size() - toRemind.size(),
                vetsNotified);
    }

    /* ------------------------------------------------------------------ */

    static boolean shouldRemind(PreventiveCareItem item, Map<ReminderKey, LocalDate> sent,
                                LocalDate today) {
        LocalDate last = sent.get(keyOf(item));
        if (last == null) {
            return true;
        }
        if ("DUE_SOON".equals(item.careStatus())) {
            return false;
        }
        return ChronoUnit.DAYS.between(last, today) >= REPEAT_AFTER_DAYS;
    }

    /** lastRecordId null (chưa từng làm) đổi thành 0, cùng quy ước với cột record_ref. */
    static ReminderKey keyOf(PreventiveCareItem item) {
        return new ReminderKey(item.horseId(), item.careType(),
                item.lastRecordId() == null ? 0 : item.lastRecordId(),
                item.careStatus());
    }
}
