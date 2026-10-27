package racehorse.backend.preventive;

/**
 * Kết quả một lần chạy nhắc lịch. Trả về cho nút chạy tay, và log ra cho tác vụ định kỳ.
 *
 * reminded = overdue + dueSoon + never: số ô được đưa vào thông báo lần này.
 * skipped  = số ô đang cần việc nhưng đã được nhắc gần đây, nên không nhắc lại.
 * vetsNotified = số bác sĩ nhận thông báo; 0 nghĩa là không có thông báo nào được gửi
 * (không có gì mới, hoặc không có bác sĩ nào đang ACTIVE).
 */
public record PreventiveReminderResult(
        int reminded,
        int overdue,
        int dueSoon,
        int never,
        int skipped,
        int vetsNotified
) {}
