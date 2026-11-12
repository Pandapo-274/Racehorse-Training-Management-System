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

    public record PhaseContext(int phaseId, int planId, String planStatus, int horseId,
            Integer distance, LocalDate startDate, LocalDate endDate) {
    }

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

    /** V37: Kiểm tra Groom có thuộc quyền quản lý của Head Trainer này không, hoặc là Groom phụ trách giáo án của Trainer. */
    public boolean isGroomOfTrainer(int groomId, int trainerId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM APP_USER u JOIN ROLE r ON r.role_id = u.role_id
                WHERE u.user_id = :groomId AND r.role_name = 'GROOM'
                  AND u.status = 'ACTIVE'
                  AND (
                      u.trainer_id = :trainerId
                      OR EXISTS (
                          SELECT 1 FROM TRAINING_PLAN tp
                          JOIN HORSE h ON h.horse_id = tp.horse_id
                          WHERE tp.groom_id = u.user_id
                            AND (tp.created_by = :trainerId OR h.trainer_id = :trainerId)
                      )
                  )
                """)
                .param("groomId", groomId)
                .param("trainerId", trainerId)
                .query(Integer.class).single() > 0;
    }

    private static final String SELECT_SESSION = """
            SELECT s.session_id, s.phase_id, ph.phase_no, ph.phase_name, s.horse_id, h.horse_name,
                   s.assigned_to, u.full_name AS assigned_to_name, s.session_date,
                   s.planned_distance, s.actual_distance, s.duration_minutes, s.avg_heart_rate,
                   s.peak_heart_rate, s.recovery_heart_rate, s.peak_velocity, s.evaluation,
                   s.remarks, s.status, ph.heart_rate_ceiling,
                   (SELECT COUNT(*) FROM THRESHOLD_ALERT a WHERE a.session_id = s.session_id) AS alert_count,
                   s.is_trial_run, s.video_url,
                   s.planned_duration, s.started_at, s.ended_at, s.early_stop_reason, s.review_status
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
                rs.getObject("heart_rate_ceiling", Integer.class), rs.getInt("alert_count"),
                rs.getBoolean("is_trial_run"), rs.getString("video_url"),
                rs.getObject("planned_duration", Integer.class),
                rs.getObject("started_at", LocalDateTime.class),
                rs.getObject("ended_at", LocalDateTime.class),
                rs.getString("early_stop_reason"),
                rs.getString("review_status"));
    }

    public Optional<TrainingSessionResponse> findById(int sessionId) {
        return jdbc.sql(SELECT_SESSION + " WHERE s.session_id = :id")
                .param("id", sessionId)
                .query(TrainingSessionRepository::mapSession)
                .optional();
    }

    public List<TrainingSessionResponse> findAll(Integer horseId, String status,
                                                 Integer assignedTo, Integer ownerId,
                                                 boolean activeHorsesOnly) {
        StringBuilder sql = new StringBuilder(SELECT_SESSION).append(" WHERE 1 = 1");
        if (horseId != null)
            sql.append(" AND s.horse_id = :horseId");
        if (status != null)
            sql.append(" AND s.status = :status");
        if (assignedTo != null)
            sql.append(" AND s.assigned_to = :assignedTo");
        if (ownerId != null)
            sql.append(" AND h.owner_id = :ownerId");
        if (activeHorsesOnly)
            sql.append(" AND h.activity_status = 'ACTIVE'");
        sql.append(" ORDER BY s.session_date DESC");

        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString());
        if (horseId != null)
            spec = spec.param("horseId", horseId);
        if (status != null)
            spec = spec.param("status", status);
        if (assignedTo != null)
            spec = spec.param("assignedTo", assignedTo);
        if (ownerId != null)
            spec = spec.param("ownerId", ownerId);
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

    public int updatePlannedDuration(int sessionId, int plannedDuration) {
        return jdbc.sql("""
                UPDATE TRAINING_SESSION
                SET planned_duration = :duration
                WHERE session_id = :id
                """)
                .param("duration", plannedDuration)
                .param("id", sessionId)
                .update();
    }

    public int markRunning(int sessionId) {
        return jdbc.sql("""
                UPDATE TRAINING_SESSION
                SET status = 'RUNNING', started_at = SYSUTCDATETIME()
                WHERE session_id = :id AND status = 'SCHEDULED'
                """)
                .param("id", sessionId)
                .update();
    }

    /** RUNNING -> COMPLETED. Ghi ended_at và đặt review_status = 'PENDING' (hạn 2 ngày). */
    public int markFinished(int sessionId) {
        return jdbc.sql("""
                UPDATE TRAINING_SESSION
                SET status = 'COMPLETED',
                    ended_at = SYSUTCDATETIME(),
                    review_status = 'PENDING'
                WHERE session_id = :id AND status = 'RUNNING'
                """)
                .param("id", sessionId)
                .update();
    }

    /** V37: Groom kết thúc sớm buổi tập (không gọi Vet) với lý do từ danh sách. */
    public int markStoppedEarly(int sessionId, String earlyStopReason) {
        return jdbc.sql("""
                UPDATE TRAINING_SESSION
                SET status = 'STOPPED_EARLY',
                    ended_at = SYSUTCDATETIME(),
                    early_stop_reason = :reason,
                    review_status = 'PENDING'
                WHERE session_id = :id AND status = 'RUNNING'
                """)
                .param("reason", earlyStopReason)
                .param("id", sessionId)
                .update();
    }

    /**
     * Đóng những buổi còn RUNNING từ ngày trước (MissedTaskJob gọi mỗi đêm).
     */
    public int closeStaleRunning(LocalDate today) {
        return jdbc.sql("""
                UPDATE TRAINING_SESSION
                SET status = 'COMPLETED',
                    ended_at = ISNULL(ended_at, SYSUTCDATETIME()),
                    review_status = CASE WHEN review_status = 'NONE' THEN 'PENDING' ELSE review_status END
                WHERE status = 'RUNNING' AND session_date < :today
                """)
                .param("today", today)
                .update();
    }

    /** V37: Tìm các buổi RUNNING đã quá thời lượng dự kiến (session_date + planned_duration <= hiện tại). */
    public List<TrainingSessionResponse> findOverdueRunningSessions(LocalDateTime now) {
        return jdbc.sql(SELECT_SESSION + """
                 WHERE s.status = 'RUNNING'
                   AND DATEADD(MINUTE, ISNULL(s.planned_duration, 60), s.session_date) <= :now
                """)
                .param("now", now)
                .query(TrainingSessionRepository::mapSession)
                .list();
    }

    /** V37: Tự động kết thúc một buổi RUNNING đã hết giờ tập và chuyển sang chờ Head Trainer review. */
    public int autoFinishOverdueRunning(int sessionId) {
        return jdbc.sql("""
                UPDATE TRAINING_SESSION
                SET status = 'COMPLETED',
                    ended_at = SYSUTCDATETIME(),
                    duration_minutes = ISNULL(duration_minutes, planned_duration),
                    review_status = 'PENDING'
                WHERE session_id = :id AND status = 'RUNNING'
                """)
                .param("id", sessionId)
                .update();
    }

    /** V37: Tự động huỷ các buổi SCHEDULED đã qua cả khung giờ tập (session_date + planned_duration <= hiện tại) mà Groom chưa Start. */
    public int autoCancelOverdueScheduled(LocalDateTime now) {
        return jdbc.sql("""
                UPDATE TRAINING_SESSION
                SET status = 'CANCELLED'
                WHERE status = 'SCHEDULED'
                  AND DATEADD(MINUTE, ISNULL(planned_duration, 60), session_date) <= :now
                """)
                .param("now", now)
                .update();
    }

    public record ScheduledSlot(int sessionId, int assignedTo, LocalDateTime sessionDate) {
    }

    public List<ScheduledSlot> findScheduledByHorse(int horseId) {
        return jdbc.sql("""
                SELECT session_id, assigned_to, session_date
                FROM TRAINING_SESSION
                WHERE horse_id = :horseId AND status = 'SCHEDULED'
                ORDER BY session_date
                """)
                .param("horseId", horseId)
                .query((rs, n) -> new ScheduledSlot(
                        rs.getInt("session_id"), rs.getInt("assigned_to"),
                        rs.getObject("session_date", LocalDateTime.class)))
                .list();
    }

    public int cancelOverdue(LocalDate today) {
        return jdbc.sql("""
                UPDATE TRAINING_SESSION SET status = 'CANCELLED'
                WHERE status = 'SCHEDULED' AND session_date < :today
                """)
                .param("today", today)
                .update();
    }

    public boolean hasRunningSession(int horseId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM TRAINING_SESSION
                WHERE horse_id = :horseId AND status = 'RUNNING'
                """)
                .param("horseId", horseId)
                .query(Integer.class).single() > 0;
    }

    /** V37: Kiểm tra Groom có đang thực hiện một buổi tập nào khác không. */
    public boolean hasRunningSessionByGroom(int groomId, int excludeSessionId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM TRAINING_SESSION
                WHERE assigned_to = :groomId AND status = 'RUNNING' AND session_id <> :excludeId
                """)
                .param("groomId", groomId)
                .param("excludeId", excludeSessionId)
                .query(Integer.class).single() > 0;
    }

    public int cancelScheduledByHorse(int horseId) {
        return jdbc.sql("""
                UPDATE TRAINING_SESSION SET status = 'CANCELLED'
                WHERE horse_id = :horseId AND status = 'SCHEDULED'
                """)
                .param("horseId", horseId)
                .update();
    }

    public int cancel(int sessionId) {
        return jdbc.sql("""
                UPDATE TRAINING_SESSION SET status = 'CANCELLED'
                WHERE session_id = :id AND status = 'SCHEDULED'
                """)
                .param("id", sessionId)
                .update();
    }

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

    /** V37: Chỉ lấy các Groom đang ACTIVE được Club Manager gán cho Head Trainer này, hoặc phụ trách giáo án của Trainer. */
    public List<GroomOption> findActiveGroomsByTrainer(int trainerId) {
        return jdbc.sql("""
                SELECT DISTINCT u.user_id, u.full_name
                FROM APP_USER u
                JOIN ROLE r ON r.role_id = u.role_id
                WHERE r.role_name = 'GROOM'
                  AND u.status = 'ACTIVE'
                  AND (
                      u.trainer_id = :trainerId
                      OR EXISTS (
                          SELECT 1 FROM TRAINING_PLAN tp
                          JOIN HORSE h ON h.horse_id = tp.horse_id
                          WHERE tp.groom_id = u.user_id
                            AND (tp.created_by = :trainerId OR h.trainer_id = :trainerId)
                      )
                  )
                ORDER BY u.full_name
                """)
                .param("trainerId", trainerId)
                .query((rs, rowNum) -> new GroomOption(
                        rs.getInt("user_id"),
                        rs.getString("full_name")))
                .list();
    }

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
