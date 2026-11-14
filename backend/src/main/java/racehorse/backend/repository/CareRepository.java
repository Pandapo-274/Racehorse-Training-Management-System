package racehorse.backend.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import racehorse.backend.entity.CareModels;

/** Chỉ nói chuyện với FEEDING_SCHEDULE và DAILY_TASK, không chứa luật nghiệp vụ. */
@Repository
public class CareRepository {

    private final JdbcClient jdbc;

    public CareRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /* ---------------- Khẩu phần ---------------- */

    public List<CareModels.FeedingItem> findFeeding(Integer horseId) {
        return findFeeding(horseId, null);
    }

    public List<CareModels.FeedingItem> findFeeding(Integer horseId, Integer ownerId) {
        StringBuilder sql = new StringBuilder("""
                SELECT f.feeding_id, f.horse_id, h.horse_name, h.stall_no, f.meal_time, f.feed_type,
                       f.quantity, f.unit, f.approved_by, u.full_name AS approver, f.note, f.supply_id
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
        if (ownerId != null) {
            sql.append(" AND h.owner_id = :ownerId");
            params.put("ownerId", ownerId);
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
                        rs.getString("note"), rs.getObject("supply_id", Integer.class)))
                .list();
    }

    public void deleteRation(int horseId) {
        jdbc.sql("DELETE FROM FEEDING_SCHEDULE WHERE horse_id = :id").param("id", horseId).update();
    }

    public void insertRationItem(int horseId, CareModels.RationItem item, int approverId) {
        jdbc.sql("""
                INSERT INTO FEEDING_SCHEDULE (horse_id, meal_time, feed_type, quantity, unit, approved_by, note,
                                              supply_id)
                VALUES (:horseId, :meal, :feed, :qty, :unit, :by, :note, :supply)
                """)
                .param("horseId", horseId)
                .param("supply", item.supplyId())
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
                   g.full_name AS groom_name, t.task_date, t.is_completed, t.completed_at, t.note,
                   t.completed_by, c.full_name AS completed_by_name
            FROM DAILY_TASK t
            JOIN HORSE h    ON h.horse_id = t.horse_id
            JOIN APP_USER g ON g.user_id  = t.assigned_to
            LEFT JOIN APP_USER c ON c.user_id = t.completed_by
            """;

    private static CareModels.Task mapTask(ResultSet rs, int n) throws SQLException {
        LocalDateTime done = rs.getObject("completed_at", LocalDateTime.class);
        Instant doneAt = done == null ? null : done.toInstant(ZoneOffset.UTC);
        LocalDate day = rs.getObject("task_date", LocalDate.class);
        boolean completed = rs.getBoolean("is_completed");
        return new CareModels.Task(
                rs.getInt("task_id"), rs.getInt("horse_id"), rs.getString("horse_name"),
                rs.getString("stall_no"), rs.getString("task_type"), rs.getInt("assigned_to"),
                rs.getString("groom_name"), day, completed, doneAt, rs.getString("note"),
                rs.getObject("completed_by", Integer.class), rs.getString("completed_by_name"),
                stateOf(completed, day, doneAt));
    }

    /**
     * OPEN / DONE / DONE_LATE / MISSED. completed_at is stored in UTC and the
     * task day is a local date, so the comparison is made in the server's zone
     * (the same zone LocalDate.now() uses everywhere else in the service).
     */
    static String stateOf(boolean completed, LocalDate day, Instant doneAt) {
        if (completed) {
            boolean late = doneAt != null && LocalDate.ofInstant(doneAt, ZoneId.systemDefault()).isAfter(day);
            return late ? "DONE_LATE" : "DONE";
        }
        return day.isBefore(LocalDate.now()) ? "MISSED" : "OPEN";
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

    /** Đánh dấu xong; có điều kiện để hai cú bấm cùng lúc chỉ một cái thắng. Clears missed_at (V25). */
    public int markDone(int taskId, int userId) {
        return jdbc.sql("""
                UPDATE DAILY_TASK SET is_completed = 1, completed_at = SYSUTCDATETIME(),
                                      completed_by = :by, missed_at = NULL
                WHERE task_id = :id AND is_completed = 0
                """).param("id", taskId).param("by", userId).update();
    }

    /** Reopens. A task whose day has passed goes straight back to missed. */
    public int markUndone(int taskId, LocalDate today) {
        return jdbc.sql("""
                UPDATE DAILY_TASK SET is_completed = 0, completed_at = NULL, completed_by = NULL,
                       missed_at = CASE WHEN task_date < :today THEN SYSUTCDATETIME() ELSE NULL END
                WHERE task_id = :id AND is_completed = 1
                """).param("id", taskId).param("today", today).update();
    }

    /** Hands an open task to another groom; the guard keeps it from moving a done task. */
    public int reassign(int taskId, int assignedTo) {
        return jdbc.sql("""
                UPDATE DAILY_TASK SET assigned_to = :to
                WHERE task_id = :id AND is_completed = 0
                """).param("id", taskId).param("to", assignedTo).update();
    }

    /** Open tasks a groom already has that day (overload warning, #15). */
    public int openTasksOf(int assignedTo, LocalDate date) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM DAILY_TASK
                WHERE assigned_to = :a AND task_date = :d AND is_completed = 0
                """).param("a", assignedTo).param("d", date).query(Integer.class).single();
    }

    /** Stamps every open task of an earlier day as missed; returns how many were newly stamped (V25). */
    public int stampMissed(LocalDate today) {
        return jdbc.sql("""
                UPDATE t SET t.missed_at = SYSUTCDATETIME()
                FROM DAILY_TASK t JOIN HORSE h ON h.horse_id = t.horse_id
                WHERE t.task_date < :today AND t.is_completed = 0 AND t.missed_at IS NULL
                  AND h.activity_status = 'ACTIVE'
                """).param("today", today).update();
    }

    /** Tasks never done, between two days (Manager report, #14). */
    public List<CareModels.Task> findMissed(LocalDate from, LocalDate to, LocalDate today) {
        return jdbc.sql(SELECT_TASK + """
                 WHERE t.task_date BETWEEN :from AND :to AND t.task_date < :today
                   AND t.is_completed = 0 AND h.activity_status = 'ACTIVE'
                 ORDER BY t.task_date DESC, g.full_name, h.stall_no
                """)
                .param("from", from).param("to", to).param("today", today)
                .query(CareRepository::mapTask).list();
    }

    /** Grooms with an open care task for this horse from `from` on (told when its ration changes). */
    public List<Integer> groomsCaringFor(int horseId, LocalDate from) {
        return jdbc.sql("""
                SELECT DISTINCT assigned_to FROM DAILY_TASK
                WHERE horse_id = :h AND is_completed = 0 AND task_date >= :d
                """).param("h", horseId).param("d", from).query(Integer.class).list();
    }

    public int deleteOpenTask(int taskId) {
        return jdbc.sql("DELETE FROM DAILY_TASK WHERE task_id = :id AND is_completed = 0")
                .param("id", taskId).update();
    }
}
