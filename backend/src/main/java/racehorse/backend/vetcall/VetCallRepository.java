package racehorse.backend.vetcall;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Chỉ nói chuyện với DB, không chứa logic nghiệp vụ. */
@Repository
public class VetCallRepository {

    private static final String SELECT_CALL = """
            SELECT c.call_id, c.alert_id, c.session_id, s.status AS session_status,
                   c.horse_id, h.horse_name, h.stall_no, h.status AS horse_status,
                   g.full_name AS groom_name,
                   a.metric_type, a.threshold_value, a.actual_value, a.severity, a.triggered_at,
                   c.called_by, cu.full_name AS called_by_name,
                   c.reason, c.observations, c.urgency, c.session_stopped, c.called_at, c.status,
                   c.answered_by, vu.full_name AS answered_by_name, c.answered_at,
                   c.decision, c.instructions, c.medication, c.report_id
            FROM VET_CALL c
            LEFT JOIN THRESHOLD_ALERT a ON a.alert_id = c.alert_id
            JOIN TRAINING_SESSION s ON s.session_id = c.session_id
            JOIN HORSE h            ON h.horse_id   = c.horse_id
            JOIN APP_USER g         ON g.user_id    = s.assigned_to
            JOIN APP_USER cu        ON cu.user_id   = c.called_by
            LEFT JOIN APP_USER vu   ON vu.user_id   = c.answered_by
            """;

    private final JdbcClient jdbc;

    public VetCallRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant utc(ResultSet rs, String column) throws SQLException {
        LocalDateTime t = rs.getObject(column, LocalDateTime.class); // lưu giờ UTC (SYSUTCDATETIME)
        return t == null ? null : t.toInstant(ZoneOffset.UTC);
    }

    private static VetCallResponse map(ResultSet rs, int n) throws SQLException {
        return new VetCallResponse(
                rs.getInt("call_id"), rs.getObject("alert_id", Integer.class), rs.getInt("session_id"),
                rs.getString("session_status"), rs.getInt("horse_id"), rs.getString("horse_name"),
                rs.getString("stall_no"), rs.getString("horse_status"), rs.getString("groom_name"),
                rs.getString("metric_type"), rs.getBigDecimal("threshold_value"),
                rs.getBigDecimal("actual_value"), rs.getString("severity"), utc(rs, "triggered_at"),
                rs.getInt("called_by"), rs.getString("called_by_name"),
                rs.getString("reason"), rs.getString("observations"), rs.getString("urgency"),
                rs.getBoolean("session_stopped"), utc(rs, "called_at"), rs.getString("status"),
                rs.getObject("answered_by", Integer.class), rs.getString("answered_by_name"),
                utc(rs, "answered_at"), rs.getString("decision"), rs.getString("instructions"),
                rs.getString("medication"), rs.getObject("report_id", Integer.class));
    }

    public Optional<VetCallResponse> findById(int callId) {
        return jdbc.sql(SELECT_CALL + " WHERE c.call_id = :id")
                .param("id", callId)
                .query(VetCallRepository::map)
                .optional();
    }

    /**
     * status null = mọi trạng thái. Ca chờ trả lời: gấp nhất lên đầu, cùng mức thì
     * ca chờ lâu nhất trước. Ca đã trả lời: mới nhất trước.
     * limit đã bị kẹp trong service nên nối thẳng vào chuỗi là an toàn.
     */
    public List<VetCallResponse> search(String status, int limit) {
        String where = status == null ? "" : " WHERE c.status = :status";
        String order = " ORDER BY CASE c.status WHEN 'PENDING' THEN 0 ELSE 1 END,"
                + " CASE WHEN c.status = 'PENDING' THEN CASE c.urgency WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END END,"
                + " CASE WHEN c.status = 'PENDING' THEN c.called_at END ASC,"
                + " c.call_id DESC";
        JdbcClient.StatementSpec spec = jdbc.sql(SELECT_CALL + where + order
                + " OFFSET 0 ROWS FETCH NEXT " + limit + " ROWS ONLY");
        if (status != null) spec = spec.param("status", status);
        return spec.query(VetCallRepository::map).list();
    }

    public int insert(int alertId, int sessionId, int horseId, int calledBy, String reason,
                      String observations, String urgency, boolean sessionStopped) {
        return insert(Integer.valueOf(alertId), sessionId, horseId, calledBy, reason, observations, urgency,
                sessionStopped, null);
    }

    /** alertId = null: a call made by a groom when stopping a session (no threshold alert). */
    public int insert(Integer alertId, int sessionId, int horseId, int calledBy, String reason,
                      String observations, String urgency, boolean sessionStopped, Integer reportId) {
        return jdbc.sql("""
                INSERT INTO VET_CALL (alert_id, session_id, horse_id, called_by, reason, observations,
                                      urgency, session_stopped, report_id)
                OUTPUT INSERTED.call_id
                VALUES (:alert, :session, :horse, :by, :reason, :obs, :urgency, :stopped, :report)
                """)
                .param("report", reportId)
                .param("alert", alertId)
                .param("session", sessionId)
                .param("horse", horseId)
                .param("by", calledBy)
                .param("reason", reason)
                .param("obs", observations)
                .param("urgency", urgency)
                .param("stopped", sessionStopped)
                .query(Integer.class)
                .single();
    }

    /**
     * PENDING -> ANSWERED. Điều kiện status nằm trong câu UPDATE: hai bác sĩ cùng
     * trả lời thì chỉ một người nhận 1 dòng, người kia nhận 0 và biết đã có người trả lời.
     */
    public int answer(int callId, int vetId, String decision, String instructions, String medication) {
        return jdbc.sql("""
                UPDATE VET_CALL
                SET status = 'ANSWERED', answered_by = :vet, answered_at = SYSUTCDATETIME(),
                    decision = :decision, instructions = :instructions, medication = :medication
                WHERE call_id = :id AND status = 'PENDING'
                """)
                .param("vet", vetId)
                .param("decision", decision)
                .param("instructions", instructions)
                .param("medication", medication)
                .param("id", callId)
                .update();
    }

    public void linkReport(int callId, int reportId) {
        jdbc.sql("UPDATE VET_CALL SET report_id = :r WHERE call_id = :id")
                .param("r", reportId)
                .param("id", callId)
                .update();
    }
}
