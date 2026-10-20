package racehorse.backend.training;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Chỉ nói chuyện với DB, không chứa logic nghiệp vụ. */
@Repository
public class TrainingSessionRepository {

    private final JdbcClient jdbc;

    public TrainingSessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Thông tin giai đoạn kèm giáo án, để service kiểm tra mà không phải query
     * nhiều lần.
     */
    public record PhaseContext(int phaseId, int planId, String planStatus, int horseId,
            Integer distance, LocalDate startDate, LocalDate endDate) {
    }

    /** Thông tin tối thiểu của Groom dùng trong biểu mẫu phân công. */
    public record GroomOption(
            int userId,
            String fullName) {
    }

    public Optional<PhaseContext> findPhaseContext(int phaseId) {
        return jdbc.sql("""
                SELECT ph.phase_id, ph.plan_id, tp.status AS plan_status, tp.horse_id,
                       ph.distance, ph.start_date, ph.end_date
                FROM PLAN_PHASE ph JOIN TRAINING_PLAN tp ON tp.plan_id = ph.plan_id
                WHERE ph.phase_id = :id
                """)
                .param("id", phaseId)
                .query((rs, n) -> new PhaseContext(
                        rs.getInt("phase_id"), rs.getInt("plan_id"), rs.getString("plan_status"),
                        rs.getInt("horse_id"), rs.getObject("distance", Integer.class),
                        rs.getObject("start_date", LocalDate.class),
                        rs.getObject("end_date", LocalDate.class)))
                .optional();
    }

    public boolean isActiveGroom(int userId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM APP_USER u JOIN ROLE r ON r.role_id = u.role_id
                WHERE u.user_id = :id AND r.role_name = 'GROOM' AND u.status = 'ACTIVE'
                """)
                .param("id", userId).query(Integer.class).single() > 0;
    }

    private static final String SELECT_SESSION = """
            SELECT s.session_id, s.phase_id, ph.phase_no, ph.phase_name, s.horse_id, h.horse_name,
                   s.assigned_to, u.full_name AS assigned_to_name, s.session_date,
                   s.planned_distance, s.actual_distance, s.duration_minutes, s.avg_heart_rate,
                   s.peak_heart_rate, s.recovery_heart_rate, s.peak_velocity, s.evaluation,
                   s.remarks, s.status, ph.heart_rate_ceiling,
                   (SELECT COUNT(*) FROM THRESHOLD_ALERT a WHERE a.session_id = s.session_id) AS alert_count
            FROM TRAINING_SESSION s
            JOIN PLAN_PHASE ph ON ph.phase_id = s.phase_id
            JOIN HORSE h       ON h.horse_id  = s.horse_id
            JOIN APP_USER u    ON u.user_id   = s.assigned_to
            """;

    private static TrainingSessionResponse mapSession(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new TrainingSessionResponse(
                rs.getInt("session_id"), rs.getInt("phase_id"), rs.getInt("phase_no"),
                rs.getString("phase_name"), rs.getInt("horse_id"), rs.getString("horse_name"),
                rs.getInt("assigned_to"), rs.getString("assigned_to_name"),
                rs.getObject("session_date", LocalDateTime.class),
                rs.getObject("planned_distance", Integer.class),
                rs.getObject("actual_distance", Integer.class),
                rs.getObject("duration_minutes", Integer.class),
                rs.getObject("avg_heart_rate", Integer.class),
                rs.getObject("peak_heart_rate", Integer.class),
                rs.getObject("recovery_heart_rate", Integer.class),
                rs.getObject("peak_velocity", BigDecimal.class),
                rs.getString("evaluation"), rs.getString("remarks"), rs.getString("status"),
                rs.getObject("heart_rate_ceiling", Integer.class), rs.getInt("alert_count"));
    }

    public Optional<TrainingSessionResponse> findById(int sessionId) {
        return jdbc.sql(SELECT_SESSION + " WHERE s.session_id = :id")
                .param("id", sessionId)
                .query(TrainingSessionRepository::mapSession)
                .optional();
    }

    /** Cả ba bộ lọc đều tùy chọn; chỉ ghép điều kiện khi có giá trị. */
    public List<TrainingSessionResponse> findAll(Integer horseId, String status, Integer assignedTo) {
        StringBuilder sql = new StringBuilder(SELECT_SESSION).append(" WHERE 1 = 1");
        if (horseId != null)
            sql.append(" AND s.horse_id = :horseId");
        if (status != null)
            sql.append(" AND s.status = :status");
        if (assignedTo != null)
            sql.append(" AND s.assigned_to = :assignedTo");
        sql.append(" ORDER BY s.session_date DESC");

        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString());
        if (horseId != null)
            spec = spec.param("horseId", horseId);
        if (status != null)
            spec = spec.param("status", status);
        if (assignedTo != null)
            spec = spec.param("assignedTo", assignedTo);
        return spec.query(TrainingSessionRepository::mapSession).list();
    }

    public int insert(int phaseId, int horseId, int assignedTo, LocalDateTime sessionDate, Integer plannedDistance) {
        return jdbc.sql("""
                INSERT INTO TRAINING_SESSION (phase_id, horse_id, assigned_to, session_date, planned_distance)
                OUTPUT INSERTED.session_id
                VALUES (:phaseId, :horseId, :assignedTo, :sessionDate, :planned)
                """)
                .param("phaseId", phaseId)
                .param("horseId", horseId)
                .param("assignedTo", assignedTo)
                .param("sessionDate", sessionDate)
                .param("planned", plannedDistance)
                .query(Integer.class)
                .single();
    }

    public void markRunning(int sessionId) {
        jdbc.sql("UPDATE TRAINING_SESSION SET status = 'RUNNING' WHERE session_id = :id")
                .param("id", sessionId).update();
    }

    /**
     * Lấy danh sách tài khoản Groom đang hoạt động.
     * Dùng cho dropdown phân công của Head Trainer.
     */
    public List<GroomOption> findActiveGrooms() {
        return jdbc.sql("""
                SELECT u.user_id, u.full_name
                FROM APP_USER u
                JOIN ROLE r ON r.role_id = u.role_id
                WHERE r.role_name = 'GROOM'
                  AND u.status = 'ACTIVE'
                ORDER BY u.full_name
                """)
                .query((rs, rowNum) -> new GroomOption(
                        rs.getInt("user_id"),
                        rs.getString("full_name")))
                .list();
    }

    /**
     * Chỉ đổi lịch khi buổi tập vẫn ở trạng thái SCHEDULED.
     * Trả về số dòng được cập nhật.
     */
    public int updateSchedule(
            int sessionId,
            int assignedTo,
            LocalDateTime sessionDate) {

        return jdbc.sql("""
                UPDATE TRAINING_SESSION
                SET assigned_to = :assignedTo,
                    session_date = :sessionDate
                WHERE session_id = :sessionId
                  AND status = 'SCHEDULED'
                """)
                .param("assignedTo", assignedTo)
                .param("sessionDate", sessionDate)
                .param("sessionId", sessionId)
                .update();
    }
}