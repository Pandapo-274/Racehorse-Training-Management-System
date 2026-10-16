package racehorse.backend.alert;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AlertRepository {

    private static final String SELECT_ALERT = """
            SELECT a.alert_id, a.session_id, s.horse_id, h.horse_name, a.metric_type,
                   a.threshold_value, a.actual_value, a.severity, a.triggered_at,
                   a.action_taken, a.resolved_by, u.full_name AS resolver_name
            FROM THRESHOLD_ALERT a
            JOIN TRAINING_SESSION s ON s.session_id = a.session_id
            JOIN HORSE h ON h.horse_id = s.horse_id
            LEFT JOIN APP_USER u ON u.user_id = a.resolved_by
            """;

    private final JdbcClient jdbc;

    public AlertRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static AlertResponse map(ResultSet rs, int rowNum) throws SQLException {
        LocalDateTime t = rs.getObject("triggered_at", LocalDateTime.class); // lưu giờ UTC
        String action = rs.getString("action_taken");
        return new AlertResponse(
                rs.getInt("alert_id"), rs.getInt("session_id"), rs.getInt("horse_id"),
                rs.getString("horse_name"), rs.getString("metric_type"),
                rs.getBigDecimal("threshold_value"), rs.getBigDecimal("actual_value"),
                rs.getString("severity"),
                t == null ? null : t.toInstant(ZoneOffset.UTC),
                action, rs.getObject("resolved_by", Integer.class), rs.getString("resolver_name"),
                action != null);
    }

    public Optional<SessionContext> findSessionContext(int sessionId) {
        return jdbc.sql("""
                SELECT s.session_id, s.assigned_to, s.status, p.heart_rate_ceiling
                FROM TRAINING_SESSION s JOIN PLAN_PHASE p ON p.phase_id = s.phase_id
                WHERE s.session_id = :id
                """)
                .param("id", sessionId)
                .query((rs, i) -> new SessionContext(rs.getInt("session_id"), rs.getInt("assigned_to"),
                        rs.getString("status"), rs.getObject("heart_rate_ceiling", Integer.class)))
                .optional();
    }

    /** Cập nhật đỉnh nhịp tim của ca tập (chỉ nâng lên, không bao giờ hạ xuống). */
    public void updatePeakHeartRate(int sessionId, int heartRate) {
        jdbc.sql("""
                UPDATE TRAINING_SESSION SET peak_heart_rate = :hr
                WHERE session_id = :id AND (peak_heart_rate IS NULL OR peak_heart_rate < :hr)
                """)
                .param("hr", heartRate)
                .param("id", sessionId)
                .update();
    }

    public void updatePeakVelocity(int sessionId, BigDecimal velocity) {
        jdbc.sql("""
                UPDATE TRAINING_SESSION SET peak_velocity = :v
                WHERE session_id = :id AND (peak_velocity IS NULL OR peak_velocity < :v)
                """)
                .param("v", velocity)
                .param("id", sessionId)
                .update();
    }

    /** Mức độ của cảnh báo chưa xử lý gần nhất trong N giây qua, dùng để chống spam. */
    public Optional<String> latestOpenSeverity(int sessionId, String metricType, int withinSeconds) {
        return jdbc.sql("""
                SELECT TOP 1 severity FROM THRESHOLD_ALERT
                WHERE session_id = :s AND metric_type = :m AND action_taken IS NULL
                  AND triggered_at > DATEADD(SECOND, -:sec, SYSUTCDATETIME())
                ORDER BY alert_id DESC
                """)
                .param("s", sessionId)
                .param("m", metricType)
                .param("sec", withinSeconds)
                .query(String.class)
                .optional();
    }

    public int insertAlert(int sessionId, String metricType, BigDecimal threshold, BigDecimal actual, String severity) {
        return jdbc.sql("""
                INSERT INTO THRESHOLD_ALERT (session_id, metric_type, threshold_value, actual_value, severity)
                OUTPUT INSERTED.alert_id
                VALUES (:s, :m, :t, :a, :sev)
                """)
                .param("s", sessionId)
                .param("m", metricType)
                .param("t", threshold)
                .param("a", actual)
                .param("sev", severity)
                .query(Integer.class)
                .single();
    }

    /**
     * afterId != null: lấy cảnh báo MỚI hơn mốc đó, cũ -> mới (dùng cho polling).
     * afterId == null: lấy N cảnh báo gần nhất, mới -> cũ (dùng khi mở màn hình lần đầu).
     */
    public List<AlertResponse> search(Integer afterId, boolean openOnly, int limit) {
        StringBuilder sql = new StringBuilder(SELECT_ALERT).append(" WHERE 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (afterId != null) {
            sql.append(" AND a.alert_id > :after");
            params.put("after", afterId);
        }
        if (openOnly) {
            sql.append(" AND a.action_taken IS NULL");
        }
        sql.append(afterId != null ? " ORDER BY a.alert_id ASC" : " ORDER BY a.alert_id DESC");
        sql.append(" OFFSET 0 ROWS FETCH NEXT ").append(limit).append(" ROWS ONLY");
        return jdbc.sql(sql.toString()).params(params).query(AlertRepository::map).list();
    }

    public Optional<AlertResponse> findById(int alertId) {
        return jdbc.sql(SELECT_ALERT + " WHERE a.alert_id = :id")
                .param("id", alertId)
                .query(AlertRepository::map)
                .optional();
    }

    /** Chỉ thành công nếu cảnh báo CHƯA ai xử lý: 2 trainer bấm cùng lúc thì chỉ 1 người thắng. */
    public boolean resolve(int alertId, String actionTaken, int userId) {
        return jdbc.sql("""
                UPDATE THRESHOLD_ALERT SET action_taken = :a, resolved_by = :u
                WHERE alert_id = :id AND action_taken IS NULL
                """)
                .param("a", actionTaken)
                .param("u", userId)
                .param("id", alertId)
                .update() == 1;
    }

    public void stopSession(int sessionId) {
        jdbc.sql("UPDATE TRAINING_SESSION SET status = 'STOPPED_EARLY' WHERE session_id = :id AND status = 'RUNNING'")
                .param("id", sessionId)
                .update();
    }
}
