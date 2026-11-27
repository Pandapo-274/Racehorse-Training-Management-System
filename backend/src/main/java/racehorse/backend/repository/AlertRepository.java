package racehorse.backend.repository;

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

import racehorse.backend.dto.AlertResponse;
import racehorse.backend.dto.SessionContext;

// SQL cho THRESHOLD_ALERT và các chỉ số trực tiếp của buổi tập.
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
                SELECT s.session_id, s.assigned_to, s.status, p.heart_rate_ceiling, s.horse_id, s.velocity_ceiling, p.target_velocity
                FROM TRAINING_SESSION s JOIN PLAN_PHASE p ON p.phase_id = s.phase_id
                WHERE s.session_id = :id
                """)
                .param("id", sessionId)
                .query((rs, i) -> new SessionContext(rs.getInt("session_id"), rs.getInt("assigned_to"),
                        rs.getString("status"), rs.getObject("heart_rate_ceiling", Integer.class),
                        rs.getInt("horse_id"), rs.getBigDecimal("velocity_ceiling"),
                        rs.getBigDecimal("target_velocity")))
                .optional();
    }

    /** Cập nhật nhịp tim đo được: tính trung bình cộng (avg_heart_rate) và cập nhật đỉnh (peak_heart_rate). */
    // Tính trung bình cộng dồn trong SQL (V44): cộng thêm vào hr_reading_sum, tăng hr_reading_count,
    // avg = sum / count. Một câu UPDATE nên không lệch khi hai lần gửi đến sát nhau.
    public void updatePeakHeartRate(int sessionId, int heartRate) {
        jdbc.sql("""
                UPDATE TRAINING_SESSION
                SET hr_reading_count = ISNULL(hr_reading_count, 0) + 1,
                    hr_reading_sum = ISNULL(hr_reading_sum, 0) + :hr,
                    avg_heart_rate = (ISNULL(hr_reading_sum, 0) + :hr) / (ISNULL(hr_reading_count, 0) + 1),
                    peak_heart_rate = CASE WHEN peak_heart_rate IS NULL OR peak_heart_rate < :hr THEN :hr ELSE peak_heart_rate END
                WHERE session_id = :id
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

    public int countHighAlertsByMetric(int sessionId, String metricType) {
        Integer cnt = jdbc.sql("""
                SELECT COUNT(*) FROM THRESHOLD_ALERT
                WHERE session_id = :s AND metric_type = :m AND severity = 'HIGH'
                """)
                .param("s", sessionId)
                .param("m", metricType)
                .query(Integer.class)
                .single();
        return cnt != null ? cnt : 0;
    }

    public int autoStopSessionOnSpeedLimit(int sessionId, String reason) {
        return jdbc.sql("""
                UPDATE TRAINING_SESSION
                SET status = 'STOPPED_EARLY',
                    early_stop_reason = :reason,
                    ended_at = SYSUTCDATETIME(),
                    review_status = 'PENDING'
                WHERE session_id = :id AND status = 'RUNNING'
                """)
                .param("reason", reason)
                .param("id", sessionId)
                .update();
    }

    /**
     * afterId != null: lấy cảnh báo MỚI hơn mốc đó, cũ -> mới (dùng cho polling).
     * afterId == null: lấy N cảnh báo gần nhất, mới -> cũ (dùng khi mở màn hình lần đầu).
     */
    public List<AlertResponse> search(Integer afterId, boolean openOnly, int limit) {
        return search(afterId, openOnly, limit, null);
    }

    // afterId dùng cho polling: frontend nhớ alert_id lớn nhất đã có, lần sau chỉ hỏi những id lớn hơn.
    // trainerId: HLV chỉ thấy cảnh báo của ngựa mình phụ trách.
    public List<AlertResponse> search(Integer afterId, boolean openOnly, int limit, Integer trainerId) {
        StringBuilder sql = new StringBuilder(SELECT_ALERT).append(" WHERE 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (afterId != null) {
            sql.append(" AND a.alert_id > :after");
            params.put("after", afterId);
        }
        if (openOnly) {
            sql.append(" AND a.action_taken IS NULL");
        }
        if (trainerId != null) {
            sql.append(" AND h.trainer_id = :trainerId");
            params.put("trainerId", trainerId);
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

    public List<AlertResponse> findBySessionId(int sessionId) {
        return jdbc.sql(SELECT_ALERT + " WHERE a.session_id = :sid ORDER BY a.alert_id DESC")
                .param("sid", sessionId)
                .query(AlertRepository::map)
                .list();
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
}
