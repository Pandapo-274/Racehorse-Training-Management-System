package racehorse.backend.training;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Chỉ nói chuyện với DB, không chứa logic nghiệp vụ. */
@Repository
public class TrainingPlanRepository {

    private final JdbcClient jdbc;

    public TrainingPlanRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT_PLAN = """
            SELECT tp.plan_id, tp.horse_id, h.horse_name, tp.plan_name, tp.start_date, tp.end_date,
                   tp.status, tp.created_by, u.full_name AS created_by_name, tp.created_at
            FROM TRAINING_PLAN tp
            JOIN HORSE h    ON h.horse_id = tp.horse_id
            JOIN APP_USER u ON u.user_id  = tp.created_by
            """;

    private static TrainingPlanResponse mapPlan(ResultSet rs, int rowNum) throws SQLException {
        return new TrainingPlanResponse(
                rs.getInt("plan_id"), rs.getInt("horse_id"), rs.getString("horse_name"),
                rs.getString("plan_name"),
                rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
                rs.getString("status"), rs.getInt("created_by"), rs.getString("created_by_name"),
                rs.getObject("created_at", LocalDateTime.class), List.of());
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
                       sessions_per_week, heart_rate_ceiling, start_date, end_date
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
                        rs.getObject("end_date", LocalDate.class)))
                .list();
    }

    /** horseId và status đều tùy chọn; chỉ ghép điều kiện khi có giá trị. */
    public List<TrainingPlanResponse.Summary> findSummaries(Integer horseId, String status) {
        StringBuilder sql = new StringBuilder("""
                SELECT tp.plan_id, tp.horse_id, h.horse_name, tp.plan_name, tp.start_date,
                       tp.end_date, tp.status,
                       (SELECT COUNT(*) FROM PLAN_PHASE p WHERE p.plan_id = tp.plan_id) AS phase_count
                FROM TRAINING_PLAN tp
                JOIN HORSE h ON h.horse_id = tp.horse_id
                WHERE 1 = 1
                """);
        if (horseId != null) sql.append(" AND tp.horse_id = :horseId");
        if (status != null)  sql.append(" AND tp.status = :status");
        sql.append(" ORDER BY tp.created_at DESC");

        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString());
        if (horseId != null) spec = spec.param("horseId", horseId);
        if (status != null)  spec = spec.param("status", status);

        return spec.query((rs, n) -> new TrainingPlanResponse.Summary(
                        rs.getInt("plan_id"), rs.getInt("horse_id"), rs.getString("horse_name"),
                        rs.getString("plan_name"),
                        rs.getObject("start_date", LocalDate.class),
                        rs.getObject("end_date", LocalDate.class),
                        rs.getString("status"), rs.getInt("phase_count")))
                .list();
    }

    public int insertPlan(int horseId, int createdBy, String planName, LocalDate startDate, LocalDate endDate) {
        return jdbc.sql("""
                INSERT INTO TRAINING_PLAN (horse_id, created_by, plan_name, start_date, end_date)
                OUTPUT INSERTED.plan_id
                VALUES (:horseId, :createdBy, :planName, :startDate, :endDate)
                """)
                .param("horseId", horseId)
                .param("createdBy", createdBy)
                .param("planName", planName)
                .param("startDate", startDate)
                .param("endDate", endDate)
                .query(Integer.class)
                .single();
    }

    public void insertPhase(int planId, int phaseNo, TrainingPlanRequest.PhaseRequest p) {
        jdbc.sql("""
                INSERT INTO PLAN_PHASE (plan_id, phase_no, phase_name, distance, workload, surface,
                                        sessions_per_week, heart_rate_ceiling, start_date, end_date)
                VALUES (:planId, :phaseNo, :phaseName, :distance, :workload, :surface,
                        :spw, :ceiling, :startDate, :endDate)
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

    public void updateStatus(int planId, String status) {
        jdbc.sql("UPDATE TRAINING_PLAN SET status = :status WHERE plan_id = :id")
                .param("status", status)
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
}