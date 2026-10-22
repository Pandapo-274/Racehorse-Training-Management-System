package racehorse.backend.notification;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationRepository {

    private final JdbcClient jdbc;

    public NotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static NotificationRow map(ResultSet rs, int rowNum) throws SQLException {
        LocalDateTime readAt = rs.getObject("read_at", LocalDateTime.class);
        return new NotificationRow(
                rs.getInt("notification_id"),
                rs.getString("notif_type"),
                rs.getString("title"),
                rs.getString("message"),
                rs.getString("target_entity"),
                rs.getObject("target_id", Integer.class),
                rs.getObject("created_at", LocalDateTime.class),
                readAt,
                readAt != null);
    }

    public void insertForUser(int userId, Notice n) {
        jdbc.sql("""
                INSERT INTO NOTIFICATION (user_id, notif_type, title, message,
                                          target_entity, target_id)
                VALUES (:userId, :type, :title, :message, :entity, :targetId)
                """)
                .param("userId", userId)
                .param("type", n.type())
                .param("title", n.title())
                .param("message", n.message())
                .param("entity", n.targetEntity())
                .param("targetId", n.targetId())
                .update();
    }

    /**
     * Một dòng cho mỗi tài khoản ACTIVE thuộc vai trò này, trong một câu lệnh.
     *
     * excludeUserId là người vừa làm việc đó: huấn luyện viên tự khoá ngựa thì
     * không cần chuông báo cho chính mình. Truyền 0 nếu không loại ai - id
     * IDENTITY bắt đầu từ 1 nên 0 không trùng ai, và tránh phải gửi NULL rồi
     * viết thêm nhánh "IS NULL OR" trong SQL.
     */
    public int insertForRole(String role, int excludeUserId, Notice n) {
        return jdbc.sql("""
                INSERT INTO NOTIFICATION (user_id, notif_type, title, message,
                                          target_entity, target_id)
                SELECT u.user_id, :type, :title, :message, :entity, :targetId
                FROM APP_USER u
                JOIN ROLE r ON r.role_id = u.role_id
                WHERE r.role_name = :role
                  AND u.status = 'ACTIVE'
                  AND u.user_id <> :exclude
                """)
                .param("type", n.type())
                .param("title", n.title())
                .param("message", n.message())
                .param("entity", n.targetEntity())
                .param("targetId", n.targetId())
                .param("role", role)
                .param("exclude", excludeUserId)
                .update();
    }

    /** Thông báo của một người, mới nhất trước. */
    public List<NotificationRow> findByUser(int userId, int limit) {
        return jdbc.sql("""
                SELECT TOP (:limit) notification_id, notif_type, title, message,
                       target_entity, target_id, created_at, read_at
                FROM NOTIFICATION
                WHERE user_id = :userId
                ORDER BY created_at DESC, notification_id DESC
                """)
                .param("limit", limit)
                .param("userId", userId)
                .query(NotificationRepository::map)
                .list();
    }

    public int countUnread(int userId) {
        return jdbc.sql("SELECT COUNT(*) FROM NOTIFICATION WHERE user_id = :userId AND read_at IS NULL")
                .param("userId", userId)
                .query(Integer.class)
                .single();
    }

    /**
     * Đánh dấu đã đọc. Điều kiện user_id nằm ngay trong câu UPDATE: không ai
     * đánh dấu được thông báo của người khác, kể cả khi đoán đúng id.
     *
     * @return 1 nếu là thông báo của người này, 0 nếu không phải (hoặc không có).
     *         Đọc lại một thông báo đã đọc vẫn trả 1 và giữ nguyên read_at cũ.
     */
    public int markRead(int notificationId, int userId) {
        return jdbc.sql("""
                UPDATE NOTIFICATION
                SET read_at = COALESCE(read_at, SYSUTCDATETIME())
                WHERE notification_id = :id AND user_id = :userId
                """)
                .param("id", notificationId)
                .param("userId", userId)
                .update();
    }

    public int markAllRead(int userId) {
        return jdbc.sql("""
                UPDATE NOTIFICATION
                SET read_at = SYSUTCDATETIME()
                WHERE user_id = :userId AND read_at IS NULL
                """)
                .param("userId", userId)
                .update();
    }
}
