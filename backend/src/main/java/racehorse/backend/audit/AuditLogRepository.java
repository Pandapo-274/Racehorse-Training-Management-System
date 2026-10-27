package racehorse.backend.audit;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Chỉ ĐỌC AUDIT_LOG. Việc ghi nằm ở UserRepository.writeAudit; ở đây cố ý không
 * có câu UPDATE hay DELETE nào: nhật ký sửa được thì không còn là bằng chứng.
 */
@Repository
public class AuditLogRepository {

    private final JdbcClient jdbc;

    public AuditLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Bộ lọc; trường nào null thì bỏ qua. since/until là mốc UTC. */
    public record Filter(Instant since, Instant until, String result, String actionPrefix,
                         String userQuery, String targetEntity, Long beforeId) {
    }

    /**
     * Lấy tối đa limit dòng, mới nhất trước. Phân trang theo log_id (keyset)
     * thay vì OFFSET: nhật ký liên tục có dòng mới chen lên đầu, OFFSET sẽ làm
     * trang sau lặp lại hoặc bỏ sót dòng.
     *
     * Chỉ nối điều kiện khi có giá trị, cùng cách TrainingSessionRepository.findAll.
     */
    public List<AuditLog.Row> search(Filter f, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT TOP (:limit)
                       a.log_id, a.created_at, a.user_id, u.username, u.full_name, r.role_name,
                       a.action, a.target_entity, a.target_id, a.result, a.ip_address
                FROM AUDIT_LOG a
                LEFT JOIN APP_USER u ON u.user_id = a.user_id
                LEFT JOIN ROLE r     ON r.role_id = u.role_id
                WHERE 1 = 1
                """);
        Map<String, Object> params = new HashMap<>();
        params.put("limit", limit);

        if (f.since() != null) {
            sql.append(" AND a.created_at >= :since");
            params.put("since", toUtc(f.since()));
        }
        if (f.until() != null) {
            sql.append(" AND a.created_at < :until");
            params.put("until", toUtc(f.until()));
        }
        if (f.result() != null) {
            sql.append(" AND a.result = :result");
            params.put("result", f.result());
        }
        if (f.actionPrefix() != null) {
            // Tiền tố như "TRAINING_PLAN_": dấu _ là ký tự đại diện của LIKE nên phải escape.
            sql.append(" AND a.action LIKE :action ESCAPE '\\'");
            params.put("action", escapeLike(f.actionPrefix()) + "%");
        }
        if (f.userQuery() != null) {
            sql.append(" AND (u.username LIKE :q ESCAPE '\\' OR u.full_name LIKE :q ESCAPE '\\')");
            params.put("q", "%" + escapeLike(f.userQuery()) + "%");
        }
        if (f.targetEntity() != null) {
            sql.append(" AND a.target_entity = :target");
            params.put("target", f.targetEntity());
        }
        if (f.beforeId() != null) {
            sql.append(" AND a.log_id < :beforeId");
            params.put("beforeId", f.beforeId());
        }
        sql.append(" ORDER BY a.log_id DESC");

        return jdbc.sql(sql.toString()).params(params)
                .query((rs, n) -> new AuditLog.Row(
                        rs.getLong("log_id"),
                        rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                        rs.getObject("user_id", Integer.class),
                        rs.getString("username"),
                        rs.getString("full_name"),
                        rs.getString("role_name"),
                        rs.getString("action"),
                        rs.getString("target_entity"),
                        rs.getObject("target_id", Integer.class),
                        rs.getString("result"),
                        rs.getString("ip_address")))
                .list();
    }

    /** Số liệu an toàn 24 giờ qua, tính trong database theo giờ UTC như cột created_at. */
    public AuditLog.Summary summary() {
        return jdbc.sql("""
                SELECT
                    SUM(CASE WHEN action = 'LOGIN' AND result = 'DENIED' THEN 1 ELSE 0 END)      AS failed_logins,
                    SUM(CASE WHEN action LIKE 'ACCESS %' AND result = 'DENIED' THEN 1 ELSE 0 END) AS denied_access,
                    COUNT(*)                                                                    AS events
                FROM AUDIT_LOG
                WHERE created_at >= DATEADD(HOUR, -24, SYSUTCDATETIME())
                """)
                .query((rs, n) -> new AuditLog.Summary(
                        rs.getInt("failed_logins"), rs.getInt("denied_access"), rs.getInt("events")))
                .single();
    }

    private static Timestamp toUtc(Instant i) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(i, ZoneOffset.UTC));
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_").replace("[", "\\[");
    }
}
