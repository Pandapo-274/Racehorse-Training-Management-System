package racehorse.backend.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import racehorse.backend.dto.TrainingPlanRequest;
import racehorse.backend.dto.TrainingPlanResponse;
import racehorse.backend.service.TrainingPlanService;

/** Chỉ nói chuyện với DB, không chứa logic nghiệp vụ. */
@Repository
public class TrainingPlanRepository {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;

    public TrainingPlanRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record PendingUpdatePayload(Integer groomId, String groomName, String planName,
                                       List<TrainingPlanRequest.PhaseRequest> phases) {}

    public record OverduePendingPlan(int planId, int horseId, String horseName,
                                     String planName, int createdBy) {}

    private static final String SELECT_PLAN = """
            SELECT tp.plan_id, tp.horse_id, h.horse_name, tp.groom_id, gr.full_name AS groom_name,
                   tp.plan_name, tp.start_date, tp.end_date,
                   tp.status, tp.created_by, u.full_name AS created_by_name, tp.created_at,
                   tp.owner_note, tp.owner_reviewed_at,
                   tp.pending_update_status, tp.pending_update_at, tp.pending_update_json
            FROM TRAINING_PLAN tp
            JOIN HORSE h    ON h.horse_id = tp.horse_id
            JOIN APP_USER u ON u.user_id  = tp.created_by
            LEFT JOIN APP_USER gr ON gr.user_id = tp.groom_id
            """;

    private static TrainingPlanResponse.PendingUpdate parsePendingUpdate(
            String status, LocalDateTime at, String json) {
        if (!"PENDING".equals(status) || json == null || json.isBlank()) {
            return null;
        }
        try {
            PendingUpdatePayload payload = JSON.readValue(json, PendingUpdatePayload.class);
            return new TrainingPlanResponse.PendingUpdate(
                    status, at, payload.groomId(), payload.groomName(), payload.planName(), payload.phases());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static TrainingPlanResponse mapPlan(ResultSet rs, int rowNum) throws SQLException {
        String pendingStatus = rs.getString("pending_update_status");
        LocalDateTime pendingAt = rs.getObject("pending_update_at", LocalDateTime.class);
        String pendingJson = rs.getString("pending_update_json");
        return new TrainingPlanResponse(
                rs.getInt("plan_id"), rs.getInt("horse_id"), rs.getString("horse_name"),
                rs.getObject("groom_id", Integer.class), rs.getString("groom_name"),
                rs.getString("plan_name"),
                rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
                rs.getString("status"), rs.getInt("created_by"), rs.getString("created_by_name"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getString("owner_note"), rs.getObject("owner_reviewed_at", LocalDateTime.class),
                List.of(),
                parsePendingUpdate(pendingStatus, pendingAt, pendingJson));
    }

    /** Trả giáo án kèm đầy đủ giai đoạn. */
    public Optional<TrainingPlanResponse> findById(int planId) {
        return jdbc.sql(SELECT_PLAN + " WHERE tp.plan_id = :id")
                .param("id", planId)
                .query(TrainingPlanRepository::mapPlan)
                .optional()
                .map(plan -> plan.withPhases(findPhases(planId)));
    }

    public List<TrainingPlanResponse.Phase> findPhases(int planId) {
        return jdbc.sql("""
                SELECT phase_id, phase_no, phase_name, distance, workload, surface,
                       sessions_per_week, heart_rate_ceiling, start_date, end_date,
                       min_gap_minutes, target_velocity
                FROM PLAN_PHASE WHERE plan_id = :id ORDER BY phase_no
                """)
                .param("id", planId)
                .query((rs, n) -> new TrainingPlanResponse.Phase(
                        rs.getInt("phase_id"), rs.getInt("phase_no"), rs.getString("phase_name"),
                        rs.getObject("distance", Integer.class), rs.getString("workload"),
                        rs.getString("surface"),
                        rs.getObject("sessions_per_week", Integer.class),
                        rs.getObject("heart_rate_ceiling", Integer.class),
                        rs.getObject("start_date", LocalDate.class),
                        rs.getObject("end_date", LocalDate.class),
                        rs.getObject("min_gap_minutes", Integer.class),
                        rs.getBigDecimal("target_velocity")))
                .list();
    }

    /** horseId và status đều tùy chọn; chỉ ghép điều kiện khi có giá trị. */
    /**
     * ownerId lọc theo chủ ngựa, dùng cho vai trò HORSE_OWNER. Lọc trong SQL,
     * không lọc danh sách ở Java sau khi đã đọc hết - cùng lý do như
     * TrainingSessionRepository.findAll.
     */
    public List<TrainingPlanResponse.Summary> findSummaries(Integer horseId, String status,
                                                            Integer ownerId) {
        StringBuilder sql = new StringBuilder("""
                SELECT tp.plan_id, tp.horse_id, h.horse_name, tp.groom_id, gr.full_name AS groom_name,
                       tp.plan_name, tp.start_date, tp.end_date, tp.status, tp.owner_note,
                       (SELECT COUNT(*) FROM PLAN_PHASE p WHERE p.plan_id = tp.plan_id) AS phase_count,
                       CASE WHEN tp.pending_update_status = 'PENDING' THEN 1 ELSE 0 END AS has_pending_update
                FROM TRAINING_PLAN tp
                JOIN HORSE h ON h.horse_id = tp.horse_id
                LEFT JOIN APP_USER gr ON gr.user_id = tp.groom_id
                WHERE 1 = 1
                """);
        if (horseId != null) sql.append(" AND tp.horse_id = :horseId");
        if (status != null)  sql.append(" AND tp.status = :status");
        if (ownerId != null) sql.append(" AND h.owner_id = :ownerId");
        sql.append(" ORDER BY tp.created_at DESC");

        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString());
        if (horseId != null) spec = spec.param("horseId", horseId);
        if (status != null)  spec = spec.param("status", status);
        if (ownerId != null) spec = spec.param("ownerId", ownerId);

        return spec.query((rs, n) -> new TrainingPlanResponse.Summary(
                        rs.getInt("plan_id"), rs.getInt("horse_id"), rs.getString("horse_name"),
                        rs.getObject("groom_id", Integer.class), rs.getString("groom_name"),
                        rs.getString("plan_name"),
                        rs.getObject("start_date", LocalDate.class),
                        rs.getObject("end_date", LocalDate.class),
                        rs.getString("status"), rs.getInt("phase_count"), rs.getString("owner_note"),
                        rs.getInt("has_pending_update") == 1))
                .list();
    }

    public int insertPlan(int horseId, int createdBy, Integer groomId, String planName, LocalDate startDate, LocalDate endDate) {
        return jdbc.sql("""
                INSERT INTO TRAINING_PLAN (horse_id, created_by, groom_id, plan_name, start_date, end_date)
                OUTPUT INSERTED.plan_id
                VALUES (:horseId, :createdBy, :groomId, :planName, :startDate, :endDate)
                """)
                .param("horseId", horseId)
                .param("createdBy", createdBy)
                .param("groomId", groomId)
                .param("planName", planName)
                .param("startDate", startDate)
                .param("endDate", endDate)
                .query(Integer.class)
                .single();
    }

    public int insertPlan(int horseId, int createdBy, String planName, LocalDate startDate, LocalDate endDate) {
        return insertPlan(horseId, createdBy, null, planName, startDate, endDate);
    }

    public int countActivePlansByGroom(int groomId) {
        try {
            Integer count = jdbc.sql("SELECT COUNT(*) FROM TRAINING_PLAN WHERE groom_id = :groomId AND status <> 'COMPLETED'")
                    .param("groomId", groomId)
                    .query(Integer.class)
                    .single();
            return count != null ? count : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public record GroomOption(int userId, String fullName) {}

    public List<GroomOption> findActiveGroomsByTrainer(int trainerId) {
        return jdbc.sql("""
                SELECT u.user_id, u.full_name
                FROM APP_USER u
                JOIN ROLE r ON r.role_id = u.role_id
                WHERE r.role_name = 'GROOM'
                  AND u.status = 'ACTIVE'
                  AND u.trainer_id = :trainerId
                ORDER BY u.full_name
                """)
                .param("trainerId", trainerId)
                .query((rs, rowNum) -> new GroomOption(
                        rs.getInt("user_id"),
                        rs.getString("full_name")))
                .list();
    }

    public List<GroomOption> findAllActiveGrooms() {
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

    public void insertPhase(int planId, int phaseNo, TrainingPlanRequest.PhaseRequest p) {
        jdbc.sql("""
                INSERT INTO PLAN_PHASE (plan_id, phase_no, phase_name, distance, workload, surface,
                                        sessions_per_week, heart_rate_ceiling, start_date, end_date,
                                        min_gap_minutes, target_velocity)
                VALUES (:planId, :phaseNo, :phaseName, :distance, :workload, :surface,
                        :spw, :ceiling, :startDate, :endDate, :gap, :targetVelocity)
                """)
                .param("planId", planId)
                .param("phaseNo", phaseNo)
                .param("phaseName", p.phaseName())
                .param("distance", p.distance())
                .param("workload", p.workload())
                .param("surface", p.surface())
                .param("spw", p.sessionsPerWeek())
                .param("ceiling", p.heartRateCeiling())
                .param("startDate", p.startDate())
                .param("endDate", p.endDate())
                .param("gap", p.minGapMinutes())
                .param("targetVelocity", p.targetVelocity())
                .update();
    }

    public int updatePlanGroomAndName(int planId, Integer groomId, String planName) {
        StringBuilder sql = new StringBuilder("UPDATE TRAINING_PLAN SET groom_id = :groomId");
        if (planName != null && !planName.isBlank()) {
            sql.append(", plan_name = :planName");
        }
        sql.append(" WHERE plan_id = :planId");
        var spec = jdbc.sql(sql.toString())
                .param("groomId", groomId)
                .param("planId", planId);
        if (planName != null && !planName.isBlank()) {
            spec = spec.param("planName", planName.trim());
        }
        return spec.update();
    }

    public int updatePhase(int phaseId, int planId, int phaseNo, TrainingPlanRequest.PhaseRequest p) {
        return jdbc.sql("""
                UPDATE PLAN_PHASE
                SET phase_no = :phaseNo,
                    phase_name = :phaseName,
                    distance = :distance,
                    workload = :workload,
                    surface = :surface,
                    sessions_per_week = :spw,
                    heart_rate_ceiling = :ceiling,
                    start_date = :startDate,
                    end_date = :endDate,
                    min_gap_minutes = :gap,
                    target_velocity = :targetVelocity
                WHERE phase_id = :phaseId AND plan_id = :planId
                """)
                .param("phaseId", phaseId)
                .param("planId", planId)
                .param("phaseNo", phaseNo)
                .param("phaseName", p.phaseName())
                .param("distance", p.distance())
                .param("workload", p.workload())
                .param("surface", p.surface())
                .param("spw", p.sessionsPerWeek())
                .param("ceiling", p.heartRateCeiling())
                .param("startDate", p.startDate())
                .param("endDate", p.endDate())
                .param("gap", p.minGapMinutes())
                .param("targetVelocity", p.targetVelocity())
                .update();
    }

    public int countSessionsInPhase(int phaseId) {
        return jdbc.sql("SELECT COUNT(*) FROM TRAINING_SESSION WHERE phase_id = :phaseId")
                .param("phaseId", phaseId)
                .query(Integer.class)
                .single();
    }

    public int deletePhase(int phaseId, int planId) {
        return jdbc.sql("DELETE FROM PLAN_PHASE WHERE phase_id = :phaseId AND plan_id = :planId")
                .param("phaseId", phaseId)
                .param("planId", planId)
                .update();
    }

    public void preparePhasesForReorder(int planId) {
        jdbc.sql("UPDATE PLAN_PHASE SET phase_no = -phase_no - 100 WHERE plan_id = :planId")
                .param("planId", planId)
                .update();
    }

    public int recordOwnerFeedback(int planId, String note) {
        return jdbc.sql("""
                UPDATE TRAINING_PLAN
                SET owner_note = :note, owner_reviewed_at = SYSUTCDATETIME()
                WHERE plan_id = :id
                """)
                .param("note", note)
                .param("id", planId)
                .update();
    }

    /**
     * Huỷ những buổi tập còn SCHEDULED của một giáo án đã kết thúc.
     *
     * Đi từ TRAINING_SESSION qua PLAN_PHASE vì buổi tập không trỏ thẳng vào
     * giáo án - nó trỏ vào giai đoạn, còn giai đoạn mới trỏ vào giáo án.
     *
     * Chỉ chạm những buổi còn SCHEDULED: buổi đã RUNNING, COMPLETED hay
     * STOPPED_EARLY là chuyện đã xảy ra, và viết lại lịch sử thì tệ hơn là để
     * một giáo án kết thúc với vài buổi dở dang.
     *
     * @return số buổi đã huỷ, để service ghi audit khi khác 0.
     */
    public int cancelRemainingSessions(int planId) {
        return jdbc.sql("""
                UPDATE s
                SET s.status = 'CANCELLED'
                FROM TRAINING_SESSION s
                JOIN PLAN_PHASE ph ON ph.phase_id = s.phase_id
                WHERE ph.plan_id = :planId AND s.status = 'SCHEDULED'
                """)
                .param("planId", planId)
                .update();
    }

    /**
     * Đổi trạng thái giáo án, với điều kiện nó vẫn đang ở trạng thái mà
     * service vừa đọc được.
     *
     * Không có điều kiện `AND status = :expected` thì hai yêu cầu đồng thời
     * đều đọc thấy ACTIVE, đều qua được bảng TRANSITIONS, rồi một cái ghi
     * PAUSED và một cái ghi COMPLETED - thứ tự ghi quyết định kết quả, và
     * giáo án có thể dừng lại ở PAUSED sau khi đã bị tuyên COMPLETED (lúc đó
     * những buổi tập còn lại đã bị huỷ). Cùng một lớp lỗi với
     * markReportExamined ở UC14.
     *
     * @return số dòng đã đổi; 0 nghĩa là có người đổi trước.
     */
    public int updateStatus(int planId, String expectedStatus, String status) {
        return jdbc.sql("""
                UPDATE TRAINING_PLAN SET status = :status
                WHERE plan_id = :id AND status = :expected
                """)
                .param("expected", expectedStatus)
                .param("status", status)
                .param("id", planId)
                .update();
    }

    /**
     * Kết thúc mọi giáo án chưa COMPLETED của một con ngựa (khi ngựa bị rút).
     *
     * Đi thẳng tới COMPLETED, kể cả từ DRAFT, không qua bảng TRANSITIONS của
     * TrainingPlanService: đây không phải Head Trainer đổi trạng thái, mà là
     * con ngựa đã rời học viện, nên không giáo án nào của nó còn chạy tiếp được.
     *
     * @return số giáo án đã kết thúc.
     */
    public int completeOpenPlansByHorse(int horseId) {
        return jdbc.sql("""
                UPDATE TRAINING_PLAN SET status = 'COMPLETED'
                WHERE horse_id = :horseId AND status <> 'COMPLETED'
                """)
                .param("horseId", horseId)
                .update();
    }

    /**
     * Xoá giáo án cùng các giai đoạn của nó, chỉ khi giáo án chưa từng chạy
     * (DRAFT, PENDING_APPROVAL, APPROVED - chưa có buổi tập nào).
     * Giai đoạn phải xoá trước (FK_PHASE_plan). Cả hai câu cùng điều kiện, và
     * service chạy trong một giao dịch: 0 dòng ở câu sau thì service ném lỗi
     * và việc xoá giai đoạn cũng được huỷ.
     *
     * @return số giáo án đã xoá (0 hoặc 1).
     */
    public int deleteDraft(int planId) {
        jdbc.sql("""
                DELETE ph FROM PLAN_PHASE ph
                JOIN TRAINING_PLAN tp ON tp.plan_id = ph.plan_id
                WHERE ph.plan_id = :id AND tp.status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED')
                """)
                .param("id", planId)
                .update();
        return jdbc.sql("""
                DELETE FROM TRAINING_PLAN
                WHERE plan_id = :id AND status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED')
                """)
                .param("id", planId)
                .update();
    }

    /**
     * Ghi quyết định của chủ ngựa: PENDING_APPROVAL -> APPROVED hoặc về DRAFT.
     * Có điều kiện PENDING_APPROVAL: Head Trainer vừa xoá thì 0 dòng.
     */
    public int recordOwnerReview(int planId, String newStatus, String note) {
        return jdbc.sql("""
                UPDATE TRAINING_PLAN
                SET status = :status, owner_note = :note, owner_reviewed_at = SYSUTCDATETIME()
                WHERE plan_id = :id AND status = 'PENDING_APPROVAL'
                """)
                .param("status", newStatus)
                .param("note", note)
                .param("id", planId)
                .update();
    }

    /** excludeId = id giáo án đang xét, để nó không tự tính là "đã có giáo án khác". */
    public boolean existsActivePlan(int horseId, int excludeId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM TRAINING_PLAN
                WHERE horse_id = :horseId AND status = 'ACTIVE' AND plan_id <> :excludeId
                """)
                .param("horseId", horseId)
                .param("excludeId", excludeId)
                .query(Integer.class).single() > 0;
    }

    /** Kiểm tra xem ngựa đã có giáo án đang mở/chưa hoàn thành (khác COMPLETED và CANCELLED). */
    public boolean existsOpenPlan(int horseId, int excludeId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM TRAINING_PLAN
                WHERE horse_id = :horseId
                  AND status NOT IN ('COMPLETED', 'CANCELLED')
                  AND plan_id <> :excludeId
                """)
                .param("horseId", horseId)
                .param("excludeId", excludeId)
                .query(Integer.class).single() > 0;
    }

    public int savePendingUpdate(int planId, PendingUpdatePayload payload) {
        String json = JSON.writeValueAsString(payload);
        return jdbc.sql("""
                UPDATE TRAINING_PLAN
                SET pending_update_status = 'PENDING',
                    pending_update_at = SYSUTCDATETIME(),
                    pending_update_json = :json
                WHERE plan_id = :id
                """)
                .param("json", json)
                .param("id", planId)
                .update();
    }

    public int clearPendingUpdate(int planId) {
        return jdbc.sql("""
                UPDATE TRAINING_PLAN
                SET pending_update_status = NULL,
                    pending_update_at = NULL,
                    pending_update_json = NULL
                WHERE plan_id = :id AND pending_update_status = 'PENDING'
                """)
                .param("id", planId)
                .update();
    }

    public List<OverduePendingPlan> findOverduePendingUpdates(LocalDateTime cutoffUtc) {
        return jdbc.sql("""
                SELECT tp.plan_id, tp.horse_id, h.horse_name, tp.plan_name, tp.created_by
                FROM TRAINING_PLAN tp
                JOIN HORSE h ON h.horse_id = tp.horse_id
                WHERE tp.pending_update_status = 'PENDING'
                  AND tp.pending_update_at < :cutoff
                """)
                .param("cutoff", cutoffUtc)
                .query((rs, n) -> new OverduePendingPlan(
                        rs.getInt("plan_id"), rs.getInt("horse_id"),
                        rs.getString("horse_name"), rs.getString("plan_name"),
                        rs.getInt("created_by")))
                .list();
    }
}
