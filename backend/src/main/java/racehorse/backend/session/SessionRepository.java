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
                   s.evaluation, s.remarks, s.evaluated_by, s.status
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
                rs.getString("status"));
    }

    /** Chỉ nối điều kiện khi có giá trị, tránh kiểu "(:x IS NULL OR ...)" dễ lỗi kiểu dữ liệu trên SQL Server. */
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
        // Lọc theo chủ ngựa cho vai trò HORSE_OWNER - trong SQL, không lọc ở Java.
        if (ownerId != null) {
            sql.append(" AND h.owner_id = :ownerId");
            params.put("ownerId", ownerId);
        }
        // limit là int đã bị kẹp trong service nên nối thẳng vào chuỗi là an toàn.
        sql.append(" ORDER BY s.session_date DESC OFFSET 0 ROWS FETCH NEXT ").append(limit).append(" ROWS ONLY");
        return jdbc.sql(sql.toString()).params(params).query(SessionRepository::map).list();
    }

    public Optional<SessionResponse> findById(int sessionId) {
        return jdbc.sql(SELECT + " WHERE s.session_id = :id")
                .param("id", sessionId)
                .query(SessionRepository::map)
                .optional();
    }

    /** Chủ ngựa của con ngựa trong một buổi tập, để kiểm quyền xem. */
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

    public void saveEvaluation(int sessionId, EvaluateSessionRequest r, String finalStatus, int evaluatorId) {
        jdbc.sql("""
                UPDATE TRAINING_SESSION
                SET actual_distance = :ad, duration_minutes = :dm, avg_heart_rate = :avg,
                    peak_heart_rate = :peak, recovery_heart_rate = :rec, peak_velocity = :vel,
                    evaluation = :ev, remarks = :rm, evaluated_by = :by, status = :st
                WHERE session_id = :id
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
                .param("id", sessionId)
                .update();
    }
}
