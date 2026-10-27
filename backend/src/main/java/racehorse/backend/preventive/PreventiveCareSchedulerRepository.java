package racehorse.backend.preventive;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Query phụ trợ cho PreventiveCareScheduler.
 *
 * Tách riêng khỏi PreventiveCareRepository vì cái kia phục vụ UC17 (đọc bảng
 * cho người dùng), còn cái này phục vụ tác vụ hệ thống chạy nền. Hai mối
 * quan tâm khác nhau: nếu sau này đổi cách chống spam (ví dụ dùng bảng riêng
 * thay vì đếm NOTIFICATION) thì chỉ sửa ở đây.
 */
@Repository
public class PreventiveCareSchedulerRepository {

    private final JdbcClient jdbc;

    public PreventiveCareSchedulerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Kiểm tra hôm nay đã gửi thông báo nhắc lịch phòng ngừa chưa.
     *
     * Đếm NOTIFICATION có notif_type = notifType được tạo từ đầu ngày (UTC)
     * tới giờ. Nếu > 0 nghĩa là đã gửi rồi, không cần gửi lại.
     *
     * CAST(SYSUTCDATETIME() AS DATE) lấy đầu ngày UTC, cùng múi giờ với
     * cột created_at (ghi bằng SYSUTCDATETIME trong trigger default).
     */
    public boolean hasReminderToday(String notifType) {
        int count = jdbc.sql("""
                SELECT COUNT(*) FROM NOTIFICATION
                WHERE notif_type = :type
                  AND created_at >= CAST(SYSUTCDATETIME() AS DATE)
                """)
                .param("type", notifType)
                .query(Integer.class)
                .single();
        return count > 0;
    }
}
