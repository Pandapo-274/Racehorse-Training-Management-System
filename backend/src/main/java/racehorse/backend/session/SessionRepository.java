package racehorse.backend.session;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SessionRepository {

    private static final String SELECT = """
            SELECT s.session_id, s.phase_id, p.phase_name, p.heart_rate_ceiling,
                   s.horse_id, h.horse_name, s.assigned_to, g.full_name AS groom_name,
                   s.session_date, s.planned_distance, s.actual_distance, s.duration_minutes,
                   s.avg_heart_rate, s.peak_heart_rate, s.recovery_heart_rate, s.peak_velocity,
                   s.evaluation, s.remarks, s.evaluated_by, s.status,
                   s.is_trial_run, s.video_url,
                   s.planned_duration, s.ended_at, s.early_stop_reason, s.review_status, s.auto_reviewed_at
            FROM TRAINING_SESSION s
            JOIN PLAN_PHASE p ON p.phase_id = s.phase_id
            JOIN HORSE h ON h.horse_id = s.horse_id
            JOIN APP_USER g ON g.user_id = s.assigned_to
            """;

    private final JdbcClient jdbc;

    public SessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static SessionResponse map(ResultSet rs, int rowNum) throws SQLException {
        LocalDateTime date = rs.getObject("session_date", LocalDateTime.class);
        return new SessionResponse(
                rs.getInt("session_id"), rs.getInt("phase_id"), rs.getString("phase_name"),
                rs.getObject("heart_rate_ceiling", Integer.class),
                rs.getInt("horse_id"), rs.getString("horse_name"),
                rs.getInt("assigned_to"), rs.getString("groom_name"),
                date,
                rs.getObject("planned_distance", Integer.class),
                rs.getObject("actual_distance", Integer.class),
                rs.getObject("duration_minutes", Integer.class),
                rs.getObject("avg_heart_rate", Integer.class),
                rs.getObject("peak_heart_rate", Integer.class),
                rs.getObject("recovery_heart_rate", Integer.class),
                rs.getBigDecimal("peak_velocity"),
                rs.getString("evaluation"), rs.getString("remarks"),
                rs.getObject("evaluated_by", Integer.class),
                rs.getString("status"),
                rs.getBoolean("is_trial_run"), rs.getString("video_url"),
                rs.getObject("planned_duration", Integer.class),
                rs.getObject("ended_at", LocalDateTime.class),
                rs.getString("early_stop_reason"),
                rs.getString("review_status"),
                rs.getObject("auto_reviewed_at", LocalDateTime.class));
    }

    public List<SessionResponse> search(String status, Integer horseId, int limit, Integer ownerId) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (status != null) {
            sql.append(" AND s.status = :status");
            params.put("status", status);
        }
        if (horseId != null) {
            sql.append(" AND s.horse_id = :horseId");
            params.put("horseId", horseId);
        }
        if (ownerId != null) {
            sql.append(" AND h.owner_id = :ownerId");
            params.put("ownerId", ownerId);
        }
        sql.append(" ORDER BY s.session_date DESC OFFSET 0 ROWS FETCH NEXT ").append(limit).append(" ROWS ONLY");
        return jdbc.sql(sql.toString()).params(params).query(SessionRepository::map).list();
    }

    public Optional<SessionResponse> findById(int sessionId) {
        return jdbc.sql(SELECT + " WHERE s.session_id = :id")
                .param("id", sessionId)
                .query(SessionRepository::map)
                .optional();
    }

    public Optional<Integer> findOwnerOfSession(int sessionId) {
        return jdbc.sql("""
                SELECT h.owner_id
                FROM TRAINING_SESSION s
                JOIN HORSE h ON h.horse_id = s.horse_id
                WHERE s.session_id = :id
                """)
                .param("id", sessionId)
                .query(Integer.class)
                .optional();
    }

    /**
     * Lưu kết quả đánh giá của Head Trainer (bao gồm cả khi xem xét lại từ khu lưu trữ ARCHIVED_AUTO).
     */
    public int saveEvaluation(int sessionId, EvaluateSessionRequest r, String expectedStatus, String finalStatus,
                              int evaluatorId) {
        return jdbc.sql("""
                UPDATE TRAINING_SESSION
                SET actual_distance = :ad, duration_minutes = :dm, avg_heart_rate = :avg,
                    peak_heart_rate = :peak, recovery_heart_rate = :rec, peak_velocity = :vel,
                    evaluation = :ev, remarks = :rm, evaluated_by = :by, status = :st,
                    review_status = 'REVIEWED',
                    ended_at = ISNULL(ended_at, SYSUTCDATETIME())
                WHERE session_id = :id AND status = :expected
                """)
                .param("ad", r.actualDistance())
                .param("dm", r.durationMinutes())
                .param("avg", r.avgHeartRate())
                .param("peak", r.peakHeartRate())
                .param("rec", r.recoveryHeartRate())
                .param("vel", r.peakVelocity())
                .param("ev", r.evaluation())
                .param("rm", r.remarks() == null || r.remarks().isBlank() ? null : r.remarks().trim())
                .param("by", evaluatorId)
                .param("st", finalStatus)
                .param("expected", expectedStatus)
                .param("id", sessionId)
                .update();
    }

    /**
     * V37: Tìm các buổi tập đã kết thúc (COMPLETED / STOPPED_EARLY) đang chờ review (PENDING)
     * nhưng đã quá hạn 2 ngày (48 tiếng) kể từ lúc kết thúc.
     */
    public List<SessionResponse> findOverduePendingReviews(LocalDateTime cutoff) {
        return jdbc.sql(SELECT + """
                 WHERE s.status IN ('COMPLETED', 'STOPPED_EARLY')
                   AND s.review_status = 'PENDING'
                   AND ISNULL(s.ended_at, DATEADD(MINUTE, ISNULL(s.planned_duration, 60), s.session_date)) <= :cutoff
                """)
                .param("cutoff", cutoff)
                .query(SessionRepository::map)
                .list();
    }

    /**
     * V37: Tạo báo cáo sơ bộ tự động từ chỉ số Groom đã nhập và chuyển vào khu lưu trữ (ARCHIVED_AUTO).
     */
    public int markAutoArchived(int sessionId, int actualDistance, int durationMinutes,
                                Integer avgHeartRate, String evaluation, String autoRemarks) {
        return jdbc.sql("""
                UPDATE TRAINING_SESSION
                SET actual_distance = ISNULL(actual_distance, :ad),
                    duration_minutes = ISNULL(duration_minutes, :dm),
                    avg_heart_rate = ISNULL(avg_heart_rate, :avg),
                    evaluation = ISNULL(evaluation, :ev),
                    remarks = :rm,
                    review_status = 'ARCHIVED_AUTO',
                    auto_reviewed_at = SYSUTCDATETIME()
                WHERE session_id = :id AND review_status = 'PENDING'
                """)
                .param("ad", actualDistance)
                .param("dm", durationMinutes)
                .param("avg", avgHeartRate)
                .param("ev", evaluation)
                .param("rm", autoRemarks)
                .param("id", sessionId)
                .update();
    }
}
