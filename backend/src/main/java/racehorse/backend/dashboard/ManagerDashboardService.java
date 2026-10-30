package racehorse.backend.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Trang "Club overview" của Club Manager: mọi số liệu đọc thẳng từ database. */
@Service
public class ManagerDashboardService {

    static final int DEFAULT_WEEKS = 8;
    private static final int OPEN_ITEM_LIMIT = 8;
    private static final List<String> HEALTH_ORDER = List.of("ELIGIBLE", "MONITOR", "INJURED", "QUARANTINE");

    private final JdbcTemplate jdbc;

    public ManagerDashboardService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ManagerDashboard.Response load(int weeks) {
        int w = Math.max(4, Math.min(weeks, 26));
        LocalDate today = LocalDate.now();
        return new ManagerDashboard.Response(
                loadSummary(today),
                loadWorkload(today, w),
                loadHealth(),
                loadOpenItems(),
                jdbc.queryForObject("SELECT COUNT(*) FROM (" + OPEN_ITEMS + ") x", Integer.class));
    }

    /* ------------------------------------------------------------------ */

    private ManagerDashboard.Summary loadSummary(LocalDate today) {
        int quarter = (today.getMonthValue() - 1) / 3;
        LocalDate quarterStart = LocalDate.of(today.getYear(), quarter * 3 + 1, 1);
        LocalDate seasonStart = LocalDate.of(today.getYear(), 1, 1);

        /* Khoảng nửa mở [đầu, đầu kỳ sau): đúng cả với cột DATE lẫn DATETIME2.
           Khoá tập chỉ tính ngựa còn ở học viện: lệnh khoá của ngựa đã rút
           không còn chặn ai tập. */
        String sql = """
                SELECT
                    (SELECT COUNT(*) FROM HORSE
                      WHERE activity_status = 'ACTIVE' AND record_type = 'ACADEMY')           AS horses,
                    (SELECT COUNT(*) FROM TRAINING_PLAN WHERE status = 'ACTIVE')               AS active_plans,
                    (SELECT COUNT(*) FROM TRAINING_LOCK l JOIN HORSE h ON h.horse_id = l.horse_id
                      WHERE l.status = 'ACTIVE' AND h.activity_status = 'ACTIVE')               AS locked_horses,
                    (SELECT ISNULL(SUM(amount), 0) FROM EXPENSE_LOG
                      WHERE expense_date >= ? AND expense_date < ?)                             AS quarter_cost,
                    (SELECT ISNULL(SUM(prize_money), 0) FROM RACE_REGISTRATION
                      WHERE race_date >= ? AND race_date < ?)                                   AS season_prize
                """;
        return jdbc.queryForObject(sql, (rs, n) -> new ManagerDashboard.Summary(
                        rs.getInt("horses"),
                        rs.getInt("active_plans"),
                        rs.getInt("locked_horses"),
                        rs.getBigDecimal("quarter_cost"),
                        "Q" + (quarter + 1) + " " + today.getYear(),
                        rs.getBigDecimal("season_prize"),
                        today.getYear()),
                Date.valueOf(quarterStart), Date.valueOf(quarterStart.plusMonths(3)),
                Date.valueOf(seasonStart), Date.valueOf(seasonStart.plusYears(1)));
    }

    /**
     * Tổng cự ly mỗi tuần, các buổi đã chạy (COMPLETED, STOPPED_EARLY).
     *
     * Tuần bắt đầu thứ Hai: ngày 0 của SQL Server (1900-01-01) là thứ Hai, nên
     * DATEDIFF(DAY, 0, d) % 7 là số ngày kể từ thứ Hai gần nhất, không phụ
     * thuộc DATEFIRST. Tuần không có buổi nào được bù 0 ở Java để biểu đồ luôn
     * đủ cột.
     */
    private List<ManagerDashboard.WorkloadPoint> loadWorkload(LocalDate today, int weeks) {
        LocalDate thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate firstMonday = thisMonday.minusWeeks(weeks - 1L);

        String sql = """
                SELECT DATEADD(DAY, -(DATEDIFF(DAY, 0, s.session_date) % 7),
                               CAST(s.session_date AS DATE))           AS week_start,
                       SUM(CAST(ISNULL(s.actual_distance, 0) AS BIGINT)) AS meters,
                       COUNT(*)                                          AS sessions
                FROM TRAINING_SESSION s
                WHERE s.status IN ('COMPLETED', 'STOPPED_EARLY')
                  AND s.session_date >= ?
                GROUP BY DATEADD(DAY, -(DATEDIFF(DAY, 0, s.session_date) % 7),
                                 CAST(s.session_date AS DATE))
                """;
        Map<LocalDate, long[]> byWeek = new HashMap<>();
        jdbc.query(sql, rs -> {
            byWeek.put(rs.getDate("week_start").toLocalDate(),
                    new long[] { rs.getLong("meters"), rs.getInt("sessions") });
        }, Date.valueOf(firstMonday));

        List<ManagerDashboard.WorkloadPoint> out = new ArrayList<>();
        for (LocalDate monday = firstMonday; !monday.isAfter(thisMonday); monday = monday.plusWeeks(1)) {
            long[] v = byWeek.getOrDefault(monday, new long[] { 0, 0 });
            BigDecimal km = BigDecimal.valueOf(v[0]).divide(BigDecimal.valueOf(1000), 1, RoundingMode.HALF_UP);
            out.add(new ManagerDashboard.WorkloadPoint(monday,
                    "W" + monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR), km, (int) v[1]));
        }
        return out;
    }

    /** Tình trạng sức khoẻ của ngựa club còn ở học viện, đủ bốn trạng thái theo thứ tự cố định. */
    private List<ManagerDashboard.HealthCount> loadHealth() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        HEALTH_ORDER.forEach(s -> counts.put(s, 0));
        jdbc.query("""
                SELECT status, COUNT(*) AS cnt FROM HORSE
                WHERE activity_status = 'ACTIVE' AND record_type = 'ACADEMY'
                GROUP BY status
                """, rs -> {
            counts.put(rs.getString("status"), rs.getInt("cnt"));
        });
        return counts.entrySet().stream()
                .map(e -> new ManagerDashboard.HealthCount(e.getKey(), e.getValue()))
                .toList();
    }

    /**
     * Việc đang chờ trong cả club. priority 1-2 là việc của chính Club Manager
     * (duyệt đăng ký, duyệt rút ngựa, nhập thêm vật tư) nên lên trước; 3 là
     * việc của vai trò khác mà Manager cần biết. Sự cố và cảnh báo của ngựa đã
     * rút không tính: không ai còn xử lý chúng nữa.
     */
    private static final String OPEN_ITEMS = """
            SELECT 'REGISTRATION' AS kind, N'Horse registration request' AS item,
                   'Club Manager' AS role_name, q.horse_name, q.created_at,
                   'Waiting for review' AS status_text, 1 AS priority
            FROM HORSE_REQUEST q
            WHERE q.status = 'PENDING'

            UNION ALL
            SELECT 'WITHDRAWAL', N'Horse withdrawal request', 'Club Manager', h.horse_name, w.created_at,
                   'Waiting for review', 1
            FROM HORSE_WITHDRAWAL w
            JOIN HORSE h ON h.horse_id = w.horse_id
            WHERE w.status = 'PENDING'

            UNION ALL
            SELECT 'SUPPLY', CONCAT(N'Restock ', s.supply_name), 'Club Manager', NULL, s.updated_at,
                   'Below minimum', 2
            FROM SUPPLY_STOCK s
            WHERE s.needs_restock = 1

            UNION ALL
            SELECT 'INCIDENT', CONCAT(N'Incident: ', r.symptoms), 'Veterinarian', h.horse_name, r.reported_at,
                   CASE r.status WHEN 'PENDING' THEN 'Waiting for vet' ELSE 'Vet is on it' END, 3
            FROM INCIDENT_REPORT r
            JOIN HORSE h ON h.horse_id = r.horse_id
            WHERE r.status IN ('PENDING', 'RECEIVED') AND h.activity_status = 'ACTIVE'

            UNION ALL
            SELECT 'ALERT',
                   CASE a.metric_type
                       WHEN 'HEART_RATE' THEN N'Heart rate over the limit'
                       WHEN 'VELOCITY'   THEN N'Speed over the limit'
                       ELSE N'Workload over the limit'
                   END,
                   'Head Trainer', h.horse_name, a.triggered_at, 'Unresolved', 3
            FROM THRESHOLD_ALERT a
            JOIN TRAINING_SESSION s ON s.session_id = a.session_id
            JOIN HORSE h ON h.horse_id = s.horse_id
            WHERE a.resolved_by IS NULL AND h.activity_status = 'ACTIVE'
            """;

    private List<ManagerDashboard.OpenItem> loadOpenItems() {
        String sql = "SELECT kind, item, role_name, horse_name, created_at, status_text FROM ("
                + OPEN_ITEMS + ") x ORDER BY priority, created_at DESC "
                + "OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY";
        return jdbc.query(sql, (rs, n) -> {
            Timestamp at = rs.getTimestamp("created_at");
            return new ManagerDashboard.OpenItem(
                    rs.getString("kind"), rs.getString("item"), rs.getString("role_name"),
                    rs.getString("horse_name"), at == null ? null : at.toLocalDateTime(),
                    rs.getString("status_text"));
        }, OPEN_ITEM_LIMIT);
    }
}
