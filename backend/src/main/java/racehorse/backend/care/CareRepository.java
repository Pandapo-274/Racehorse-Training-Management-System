package racehorse.backend.care;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Chỉ nói chuyện với FEEDING_SCHEDULE và DAILY_TASK, không chứa luật nghiệp vụ. */
@Repository
public class CareRepository {

    private final JdbcClient jdbc;

    public CareRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /* ---------------- Khẩu phần ---------------- */

    public List<CareModels.FeedingItem> findFeeding(Integer horseId) {
        StringBuilder sql = new StringBuilder("""
                SELECT f.feeding_id, f.horse_id, h.horse_name, h.stall_no, f.meal_time, f.feed_type,
                       f.quantity, f.unit, f.approved_by, u.full_name AS approver, f.note
                FROM FEEDING_SCHEDULE f
                JOIN HORSE h ON h.horse_id = f.horse_id
                LEFT JOIN APP_USER u ON u.user_id = f.approved_by
                WHERE h.activity_status = 'ACTIVE'
                """);
        Map<String, Object> params = new HashMap<>();
        if (horseId != null) {
            sql.append(" AND f.horse_id = :horseId");
            params.put("horseId", horseId);
        }
        sql.append("""
                 ORDER BY h.stall_no, h.horse_name,
                          CASE f.meal_time WHEN 'MORNING' THEN 1 WHEN 'NOON' THEN 2 ELSE 3 END, f.feeding_id
                """);
        return jdbc.sql(sql.toString()).params(params)
                .query((rs, n) -> new CareModels.FeedingItem(
                        rs.getInt("feeding_id"), rs.getInt("horse_id"), rs.getString("horse_name"),
                        rs.getString("stall_no"), rs.getString("meal_time"), rs.getString("feed_type"),
                        rs.getBigDecimal("quantity"), rs.getString("unit"),
                        rs.getObject("approved_by", Integer.class), rs.getString("approver"),
                        rs.getString("note")))
                .list();
    }

    public void deleteRation(int horseId) {
        jdbc.sql("DELETE FROM FEEDING_SCHEDULE WHERE horse_id = :id").param("id", horseId).update();
    }

    public void insertRationItem(int horseId, CareModels.RationItem item, int approverId) {
        jdbc.sql("""
                INSERT INTO FEEDING_SCHEDULE (horse_id, meal_time, feed_type, quantity, unit, approved_by, note)
                VALUES (:horseId, :meal, :feed, :qty, :unit, :by, :note)
                """)
                .param("horseId", horseId)
                .param("meal", item.mealTime())
                .param("feed", item.feedType().trim())
                .param("qty", item.quantity())
                .param("unit", item.unit().trim())
                .param("by", approverId)
                .param("note", item.note() == null || item.note().isBlank() ? null : item.note().trim())
                .update();
    }

    /* ---------------- Việc trong ngày ---------------- */

    private static final String SELECT_TASK = """
            SELECT t.task_id, t.horse_id, h.horse_name, h.stall_no, t.task_type, t.assigned_to,
                   g.full_name AS groom_name, t.task_date, t.is_completed, t.completed_at, t.note
            FROM DAILY_TASK t
            JOIN HORSE h    ON h.horse_id = t.horse_id
            JOIN APP_USER g ON g.user_id  = t.assigned_to
            """;

    private static CareModels.Task mapTask(ResultSet rs, int n) throws SQLException {
        LocalDateTime done = rs.getObject("completed_at", LocalDateTime.class);
        return new CareModels.Task(
                rs.getInt("task_id"), rs.getInt("horse_id"), rs.getString("horse_name"),
                rs.getString("stall_no"), rs.getString("task_type"), rs.getInt("assigned_to"),
                rs.getString("groom_name"), rs.getObject("task_date", LocalDate.class),
                rs.getBoolean("is_completed"),
                done == null ? null : done.toInstant(ZoneOffset.UTC),
                rs.getString("note"));
    }

    public List<CareModels.Task> findTasks(LocalDate date, Integer assignedTo, Integer horseId) {
        // Ngựa đã rút (V15) không còn trong chuồng nên không còn việc chăm sóc.
        StringBuilder sql = new StringBuilder(SELECT_TASK)
                .append(" WHERE t.task_date = :date AND h.activity_status = 'ACTIVE'");
        Map<String, Object> params = new HashMap<>();
        params.put("date", date);
        if (assignedTo != null) {
            sql.append(" AND t.assigned_to = :assignedTo");
            params.put("assignedTo", assignedTo);
        }
        if (horseId != null) {
            sql.append(" AND t.horse_id = :horseId");
            params.put("horseId", horseId);
        }
        sql.append("""
                 ORDER BY h.stall_no, h.horse_name,
                          CASE t.task_type WHEN 'FEEDING' THEN 1 WHEN 'CLEANING' THEN 2
                                           WHEN 'BATHING' THEN 3 ELSE 4 END
                """);
        return jdbc.sql(sql.toString()).params(params).query(CareRepository::mapTask).list();
    }

    public Optional<CareModels.Task> findTask(int taskId) {
        return jdbc.sql(SELECT_TASK + " WHERE t.task_id = :id")
                .param("id", taskId).query(CareRepository::mapTask).optional();
    }

    public boolean taskExists(int horseId, String taskType, LocalDate date) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM DAILY_TASK WHERE horse_id = :h AND task_type = :t AND task_date = :d
                """)
                .param("h", horseId).param("t", taskType).param("d", date)
                .query(Integer.class).single() > 0;
    }

    public int insertTask(int horseId, String taskType, int assignedTo, LocalDate date, String note) {
        return jdbc.sql("""
                INSERT INTO DAILY_TASK (horse_id, task_type, assigned_to, task_date, note)
                OUTPUT INSERTED.task_id
                VALUES (:h, :t, :a, :d, :note)
                """)
                .param("h", horseId).param("t", taskType).param("a", assignedTo)
                .param("d", date).param("note", note)
                .query(Integer.class).single();
    }

    /** Đánh dấu xong; có điều kiện để hai cú bấm cùng lúc chỉ một cái thắng. */
    public int markDone(int taskId) {
        return jdbc.sql("""
                UPDATE DAILY_TASK SET is_completed = 1, completed_at = SYSUTCDATETIME()
                WHERE task_id = :id AND is_completed = 0
                """).param("id", taskId).update();
    }

    public int markUndone(int taskId) {
        return jdbc.sql("""
                UPDATE DAILY_TASK SET is_completed = 0, completed_at = NULL
                WHERE task_id = :id AND is_completed = 1
                """).param("id", taskId).update();
    }

    public int deleteOpenTask(int taskId) {
        return jdbc.sql("DELETE FROM DAILY_TASK WHERE task_id = :id AND is_completed = 0")
                .param("id", taskId).update();
    }
}
