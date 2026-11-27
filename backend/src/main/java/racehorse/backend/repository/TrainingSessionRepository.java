package racehorse.backend.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import racehorse.backend.dto.TrainingSessionResponse;
import racehorse.backend.service.MissedTaskJob;

/** Chỉ nói chuyện với DB, không chứa logic nghiệp vụ. */
@Repository
public class TrainingSessionRepository {

    private final JdbcClient jdbc;

    public TrainingSessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // Ngữ cảnh của một giai đoạn khi xếp lịch. Constructor phụ (không có targetVelocity) cho code cũ.
    public record PhaseContext(int phaseId, int planId, String planStatus, int horseId,
            Integer distance, BigDecimal targetVelocity, LocalDate startDate, LocalDate endDate) {
        public PhaseContext(int phaseId, int planId, String planStatus, int horseId,
                Integer distance, LocalDate startDate, LocalDate endDate) {
            this(phaseId, planId, planStatus, horseId, distance, null, startDate, endDate);
        }
    }

    public record GroomOption(
            int userId,
            String fullName) {
    }

    public Optional<PhaseContext> findPhaseContext(int phaseId) {
        return jdbc.sql("""
                SELECT ph.phase_id, ph.plan_id, tp.status AS plan_status, tp.horse_id,
                       ph.distance, ph.target_velocity, ph.start_date, ph.end_date
                FROM PLAN_PHASE ph JOIN TRAINING_PLAN tp ON tp.plan_id = ph.plan_id
                WHERE ph.phase_id = :id
                """)
                .param("id", phaseId)
                .query((rs, n) -> new PhaseContext(
                        rs.getInt("phase_id"), rs.getInt("plan_id"), rs.getString("plan_status"),
                        rs.getInt("horse_id"), rs.getObject("distance", Integer.class),
                        rs.getBigDecimal("target_velocity"),
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

    /** Groom thuộc đội của Head Trainer này (APP_USER.trainer_id, do Club Manager phân công). */
    // Góp ý hội đồng: trước đây Groom từng có giáo án với HLV cũng tính là "thuộc", kể cả giáo án đã
    // xong, nên HLV vẫn chọn được Groom đã chuyển đội.
    public boolean isGroomOfTrainer(int groomId, int trainerId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM APP_USER u JOIN ROLE r ON r.role_id = u.role_id
                WHERE u.user_id = :groomId AND r.role_name = 'GROOM'
                  AND u.status = 'ACTIVE'
                  AND u.trainer_id = :trainerId
                """)
                .param("groomId", groomId)
                .param("trainerId", trainerId)
                .query(Integer.class).single() > 0;
    }

    // SELECT dùng chung cho mọi câu đọc buổi tập; alert_count đếm số cảnh báo của buổi bằng subquery.
    private static final String SELECT_SESSION = """
            SELECT s.session_id, s.phase_id, ph.phase_no, ph.phase_name, s.horse_id, h.horse_name,
                   s.assigned_to, u.full_name AS assigned_to_name, s.session_date,
                   s.planned_distance, s.actual_distance, s.duration_minutes, s.avg_heart_rate,
                   s.peak_heart_rate, s.recovery_heart_rate, s.peak_velocity, s.evaluation,
                   s.remarks, s.status, ph.heart_rate_ceiling,
                   (SELECT COUNT(*) FROM THRESHOLD_ALERT a WHERE a.session_id = s.session_id) AS alert_count,
                   s.is_trial_run, s.video_url,
                   s.planned_duration, s.started_at, s.ended_at, s.early_stop_reason, s.review_status,
                   s.velocity_ceiling,
                   s.slot_type, s.conflict_alert,
                   COALESCE(s.recovery_duration_minutes, ph.recovery_duration_minutes, 15) AS recovery_duration_minutes,
                   s.recovery_started_at, s.recovery_ends_at,
                   s.actual_hrr, s.overload_warning, tp.target_hrr
            FROM TRAINING_SESSION s
            JOIN PLAN_PHASE ph   ON ph.phase_id = s.phase_id
            JOIN TRAINING_PLAN tp ON tp.plan_id = ph.plan_id
            JOIN HORSE h         ON h.horse_id  = s.horse_id
            LEFT JOIN APP_USER u ON u.user_id   = s.assigned_to
            """;

    private static TrainingSessionResponse mapSession(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        Integer assignedObj = rs.getObject("assigned_to", Integer.class);
        Boolean conflictObj = rs.getObject("conflict_alert", Boolean.class);
        Boolean overloadObj = rs.getObject("overload_warning", Boolean.class);
        return new TrainingSessionResponse(
                rs.getInt("session_id"), rs.getInt("phase_id"), rs.getInt("phase_no"),
                rs.getString("phase_name"), rs.getInt("horse_id"), rs.getString("horse_name"),
                assignedObj != null ? assignedObj : 0, rs.getString("assigned_to_name"),
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
                rs.getString("review_status"),
                rs.getBigDecimal("velocity_ceiling"),
                rs.getString("slot_type"),
                conflictObj != null && conflictObj,
                rs.getObject("recovery_duration_minutes", Integer.class),
                rs.getObject("recovery_started_at", LocalDateTime.class),
                rs.getObject("recovery_ends_at", LocalDateTime.class),
                rs.getObject("actual_hrr", Integer.class),
                overloadObj != null && overloadObj,
                rs.getObject("target_hrr", Integer.class));
    }

    public Optional<TrainingSessionResponse> findById(int sessionId) {
        return jdbc.sql(SELECT_SESSION + " WHERE s.session_id = :id")
                .param("id", sessionId)
                .query(TrainingSessionRepository::mapSession)
                .optional();
    }

    // Lọc động theo các tham số không null. activeHorsesOnly: bỏ ngựa đã rút (dùng cho Groom).
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

    public int insert(int phaseId, int horseId, int assignedTo, LocalDateTime sessionDate, Integer plannedDistance, String remarks) {
        return jdbc.sql("""
                INSERT INTO TRAINING_SESSION (phase_id, horse_id, assigned_to, session_date, planned_distance, remarks)
                OUTPUT INSERTED.session_id
                VALUES (:phaseId, :horseId, :assignedTo, :sessionDate, :planned, :remarks)
                """)
                .param("phaseId", phaseId)
                .param("horseId", horseId)
                .param("assignedTo", assignedTo)
                .param("sessionDate", sessionDate)
                .param("planned", plannedDistance)
                .param("remarks", remarks)
                .query(Integer.class)
                .single();
    }

    public int insert(int phaseId, int horseId, int assignedTo, LocalDateTime sessionDate, Integer plannedDistance) {
        return insert(phaseId, horseId, assignedTo, sessionDate, plannedDistance, null);
    }

    public int updateVelocityCeiling(int sessionId, BigDecimal velocityCeiling) {
        return jdbc.sql("""
                UPDATE TRAINING_SESSION
                SET velocity_ceiling = :ceiling
                WHERE session_id = :id
                """)
                .param("ceiling", velocityCeiling)
                .param("id", sessionId)
                .update();
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

    public int updateVideoUrl(int sessionId, String videoUrl) {
        return jdbc.sql("""
                UPDATE TRAINING_SESSION
                SET video_url = :url
                WHERE session_id = :id
                """)
                .param("url", videoUrl)
                .param("id", sessionId)
                .update();
    }

    // "AND status = 'SCHEDULED'" trong UPDATE: hai người bấm Start cùng lúc thì chỉ một người thành
    // công (người kia nhận 0 dòng -> 409). Các hàm mark... bên dưới dùng cùng kỹ thuật.
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

    /** V37/V66: Tìm các buổi RUNNING đã quá thời lượng dự kiến + 5 phút ân hạn (session_date + planned_duration + 5 <= hiện tại). */
    public List<TrainingSessionResponse> findOverdueRunningSessions(LocalDateTime now) {
        return jdbc.sql(SELECT_SESSION + """
                 WHERE s.status = 'RUNNING'
                   AND DATEADD(MINUTE, ISNULL(s.planned_duration, 60) + 5, s.session_date) <= :now
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

    /** Tự động chuyển buổi RUNNING quá hạn + 5 phút (đã có thông số) sang giai đoạn RECOVERY. */
    public int markAutoRecoveryStarted(int sessionId, int recoveryMinutes, LocalDateTime now, LocalDateTime endsAt) {
        return jdbc.sql("""
                UPDATE TRAINING_SESSION
                SET status = 'RECOVERY',
                    duration_minutes = ISNULL(duration_minutes, planned_duration),
                    recovery_duration_minutes = :recMin,
                    recovery_started_at = :now,
                    recovery_ends_at = :endsAt
                WHERE session_id = :id AND status = 'RUNNING'
                """)
                .param("recMin", recoveryMinutes)
                .param("now", now)
                .param("endsAt", endsAt)
                .param("id", sessionId)
                .update();
    }

    /** Kết thúc thẳng với trạng thái FAIL (STOPPED_EARLY + POOR) khi hết giờ + 5 phút mà không có thông số nào. */
    public int autoFailOverdueRunning(int sessionId, String reason, String note) {
        return jdbc.sql("""
                UPDATE TRAINING_SESSION
                SET status = 'STOPPED_EARLY',
                    ended_at = SYSUTCDATETIME(),
                    duration_minutes = ISNULL(duration_minutes, planned_duration),
                    early_stop_reason = :reason,
                    evaluation = 'POOR',
                    review_status = 'PENDING',
                    remarks = CASE
                        WHEN remarks IS NULL OR LEN(LTRIM(RTRIM(remarks))) = 0 THEN :note
                        ELSE remarks + CHAR(10) + :note
                    END
                WHERE session_id = :id AND status = 'RUNNING'
                """)
                .param("reason", reason)
                .param("note", note)
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
                    session_date = :sessionDate,
                    status = CASE WHEN status = 'UNASSIGNED' THEN 'SCHEDULED' ELSE status END,
                    conflict_alert = 0
                WHERE session_id = :sessionId
                  AND status IN ('SCHEDULED', 'UNASSIGNED')
                """)
                .param("assignedTo", assignedTo)
                .param("sessionDate", sessionDate)
                .param("sessionId", sessionId)
                .update();
    }

    public int countSessionsByPlan(int planId) {
        Integer count = jdbc.sql("""
                SELECT COUNT(*)
                FROM TRAINING_SESSION s
                JOIN PLAN_PHASE ph ON ph.phase_id = s.phase_id
                WHERE ph.plan_id = :planId
                """)
                .param("planId", planId)
                .query(Integer.class)
                .single();
        return count != null ? count : 0;
    }

    public int deleteUnstartedByPlan(int planId) {
        return jdbc.sql("""
                DELETE s
                FROM TRAINING_SESSION s
                JOIN PLAN_PHASE ph ON ph.phase_id = s.phase_id
                WHERE ph.plan_id = :planId
                  AND s.status IN ('SCHEDULED', 'UNASSIGNED', 'REST')
                """)
                .param("planId", planId)
                .update();
    }

    public boolean isGroomBusyOnDate(int groomId, LocalDate day) {
        Integer count = jdbc.sql("""
                SELECT COUNT(*)
                FROM TRAINING_SESSION
                WHERE assigned_to = :groomId
                  AND CAST(session_date AS DATE) = :day
                  AND status IN ('SCHEDULED', 'RUNNING', 'RECOVERY', 'COMPLETED', 'STOPPED_EARLY')
                """)
                .param("groomId", groomId)
                .param("day", day)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    public int insertAutoSlot(int phaseId, int horseId, Integer assignedTo, LocalDateTime sessionDate,
                              Integer plannedDistance, int plannedDuration, BigDecimal velocityCeiling,
                              String status, String slotType, boolean conflictAlert,
                              Integer recoveryDurationMinutes, String remarks) {
        return jdbc.sql("""
                INSERT INTO TRAINING_SESSION (
                    phase_id, horse_id, assigned_to, session_date, planned_distance,
                    planned_duration, velocity_ceiling, status, slot_type, conflict_alert,
                    recovery_duration_minutes, remarks
                )
                OUTPUT INSERTED.session_id
                VALUES (
                    :phaseId, :horseId, :assignedTo, :sessionDate, :plannedDistance,
                    :plannedDuration, :velocityCeiling, :status, :slotType, :conflictAlert,
                    :recoveryDurationMinutes, :remarks
                )
                """)
                .param("phaseId", phaseId)
                .param("horseId", horseId)
                .param("assignedTo", assignedTo)
                .param("sessionDate", sessionDate)
                .param("plannedDistance", plannedDistance)
                .param("plannedDuration", plannedDuration)
                .param("velocityCeiling", velocityCeiling)
                .param("status", status)
                .param("slotType", slotType)
                .param("conflictAlert", conflictAlert)
                .param("recoveryDurationMinutes", recoveryDurationMinutes)
                .param("remarks", remarks)
                .query(Integer.class)
                .single();
    }

    public Optional<Integer> findPhaseRecoveryMinutes(int phaseId) {
        return jdbc.sql("SELECT recovery_duration_minutes FROM PLAN_PHASE WHERE phase_id = :id")
                .param("id", phaseId)
                .query(Integer.class)
                .optional();
    }

    public Optional<Integer> findPlanTargetHrr(int phaseId) {
        return jdbc.sql("""
                SELECT tp.target_hrr
                FROM TRAINING_PLAN tp
                JOIN PLAN_PHASE ph ON ph.plan_id = tp.plan_id
                WHERE ph.phase_id = :id
                """)
                .param("id", phaseId)
                .query(Integer.class)
                .optional();
    }

    public int markRecoveryStarted(int sessionId, int recoveryDurationMinutes,
                                   LocalDateTime startedAt, LocalDateTime endsAt) {
        return markRecoveryStarted(sessionId, recoveryDurationMinutes, startedAt, endsAt, null);
    }

    public int markRecoveryStarted(int sessionId, int recoveryDurationMinutes,
                                   LocalDateTime startedAt, LocalDateTime endsAt, String earlyStopReason) {
        return jdbc.sql("""
                UPDATE TRAINING_SESSION
                SET status = 'RECOVERY',
                    recovery_duration_minutes = :mins,
                    recovery_started_at = :startedAt,
                    recovery_ends_at = :endsAt,
                    early_stop_reason = ISNULL(:earlyReason, early_stop_reason)
                WHERE session_id = :id AND status = 'RUNNING'
                """)
                .param("mins", recoveryDurationMinutes)
                .param("startedAt", startedAt)
                .param("endsAt", endsAt)
                .param("earlyReason", earlyStopReason)
                .param("id", sessionId)
                .update();
    }

    public List<TrainingSessionResponse> findOverdueRecoverySessions(LocalDateTime now) {
        return jdbc.sql(SELECT_SESSION + """
                 WHERE s.status = 'RECOVERY'
                   AND s.recovery_ends_at IS NOT NULL
                   AND DATEADD(MINUTE, 5, s.recovery_ends_at) <= :now
                """)
                .param("now", now)
                .query(TrainingSessionRepository::mapSession)
                .list();
    }

    public int autoCompleteOverdueRecovery(int sessionId, String warningNote) {
        return jdbc.sql("""
                UPDATE TRAINING_SESSION
                SET status = 'COMPLETED',
                    overload_warning = 1,
                    ended_at = ISNULL(ended_at, SYSUTCDATETIME()),
                    review_status = 'PENDING',
                    remarks = CASE
                        WHEN remarks IS NOT NULL AND LTRIM(RTRIM(remarks)) <> ''
                        THEN remarks + ' | ' + :warningNote
                        ELSE :warningNote
                    END
                WHERE session_id = :id AND status = 'RECOVERY'
                """)
                .param("warningNote", warningNote)
                .param("id", sessionId)
                .update();
    }

    public int markRecoveryCompleted(int sessionId, int actualHrr, boolean overloadWarning, String remarks) {
        return jdbc.sql("""
                UPDATE TRAINING_SESSION
                SET status = 'COMPLETED',
                    actual_hrr = :actualHrr,
                    recovery_heart_rate = :actualHrr,
                    overload_warning = :overloadWarning,
                    ended_at = ISNULL(ended_at, SYSUTCDATETIME()),
                    review_status = 'PENDING',
                    remarks = CASE
                        WHEN :remarks IS NOT NULL AND LTRIM(RTRIM(:remarks)) <> ''
                        THEN COALESCE(remarks + ' | ', '') + :remarks
                        ELSE remarks
                    END
                WHERE session_id = :id AND status IN ('RECOVERY', 'RUNNING')
                """)
                .param("actualHrr", actualHrr)
                .param("overloadWarning", overloadWarning)
                .param("remarks", remarks)
                .param("id", sessionId)
                .update();
    }
}
