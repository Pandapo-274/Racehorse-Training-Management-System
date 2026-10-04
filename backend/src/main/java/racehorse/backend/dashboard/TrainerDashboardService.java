package racehorse.backend.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Truy vấn dữ liệu cho UC6.
 *
 * CHỈ SỐ THỂ LỰC ĐƯỢC SUY RA, KHÔNG CÓ SẴN TRONG DATABASE.
 * Schema không có cột fitness_index. Chỉ số ở đây tính từ recovery_heart_rate -
 * nhịp tim đo 2 phút sau khi kết thúc buổi tập. Hồi phục càng nhanh thì thể lực
 * càng tốt, đây là thước đo tiêu chuẩn trong thể thao sức bền.
 *
 * Quy đổi tuyến tính: 80 bpm = 100 điểm, 140 bpm = 0 điểm, cắt biên ở 0 và 100.
 * Hằng số nằm trong FITNESS_EXPR bên dưới, đổi ngưỡng thì sửa một chỗ duy nhất.
 *
 * Nếu sau này nhóm muốn chỉ số theo công thức khác (ví dụ kết hợp cả vận tốc
 * đỉnh), sửa đúng biểu thức này - ba câu query đều dùng chung nó.
 */
@Service
public class TrainerDashboardService {

    /** Công thức chỉ số thể lực, dùng lại trong nhiều query. */
    private static final String FITNESS_EXPR = """
            CASE
                WHEN s.recovery_heart_rate IS NULL THEN NULL
                WHEN s.recovery_heart_rate <= 80  THEN 100
                WHEN s.recovery_heart_rate >= 140 THEN 0
                ELSE CAST(ROUND((140.0 - s.recovery_heart_rate) * 100.0 / 60.0, 0) AS INT)
            END
            """;

    private static final DateTimeFormatter WEEK_LABEL = DateTimeFormatter.ofPattern("dd/MM");

    private final JdbcTemplate jdbc;

    public TrainerDashboardService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public TrainerDashboard.Response load(int weeks) {
        return new TrainerDashboard.Response(
                loadSummary(),
                loadFitnessTrend(weeks),
                loadStatusBreakdown(),
                loadHorses(),
                loadOpenAlerts());
    }

    /* ------------------------------------------------------------------ */

    private TrainerDashboard.Summary loadSummary() {
        String sql = """
                SELECT
                    (SELECT COUNT(*) FROM HORSE)                                        AS total_horses,
                    (SELECT COUNT(*) FROM TRAINING_PLAN WHERE status = 'ACTIVE')        AS active_plans,
                    (SELECT COUNT(*) FROM TRAINING_LOCK WHERE status = 'ACTIVE')        AS locked_horses,
                    (SELECT COUNT(*) FROM THRESHOLD_ALERT WHERE resolved_by IS NULL)    AS open_alerts
                """;
        return jdbc.queryForObject(sql, (rs, n) -> new TrainerDashboard.Summary(
                rs.getInt("total_horses"),
                rs.getInt("active_plans"),
                rs.getInt("locked_horses"),
                rs.getInt("open_alerts")));
    }

    /**
     * Chỉ số thể lực trung bình toàn đàn theo tuần.
     *
     * DATEADD(WEEK, DATEDIFF(WEEK, 0, ...), 0) đưa mọi ngày trong cùng một tuần
     * về chung một mốc đầu tuần - cách gom tuần chuẩn của SQL Server, không phụ
     * thuộc DATEFIRST như DATEPART(WEEK, ...).
     */
    private List<TrainerDashboard.FitnessPoint> loadFitnessTrend(int weeks) {
        String sql = """
                SELECT
                    DATEADD(WEEK, DATEDIFF(WEEK, 0, s.session_date), 0) AS week_start,
                    AVG(CAST(%s AS FLOAT))                              AS avg_fitness,
                    COUNT(*)                                            AS session_count
                FROM TRAINING_SESSION s
                WHERE s.status IN ('COMPLETED', 'STOPPED_EARLY')
                  AND s.recovery_heart_rate IS NOT NULL
                  AND s.session_date >= DATEADD(WEEK, ?, CAST(GETDATE() AS DATE))
                GROUP BY DATEADD(WEEK, DATEDIFF(WEEK, 0, s.session_date), 0)
                ORDER BY week_start
                """.formatted(FITNESS_EXPR);

        // Truyen so am thay vi viet "-?" trong SQL: mot tham so dat ngay sau dau
        // tru la cho trinh phan tich cu phap de nham nhat.
        return jdbc.query(sql, (rs, n) -> {
            LocalDate weekStart = rs.getTimestamp("week_start").toLocalDateTime().toLocalDate();
            return new TrainerDashboard.FitnessPoint(
                    weekStart,
                    weekStart.format(WEEK_LABEL),
                    (int) Math.round(rs.getDouble("avg_fitness")),
                    rs.getInt("session_count"));
        }, -weeks);
    }

    private List<TrainerDashboard.StatusCount> loadStatusBreakdown() {
        String sql = """
                SELECT status, COUNT(*) AS cnt
                FROM HORSE
                GROUP BY status
                ORDER BY status
                """;
        return jdbc.query(sql, (rs, n) -> new TrainerDashboard.StatusCount(
                rs.getString("status"),
                rs.getInt("cnt")));
    }

    /**
     * Một dòng mỗi con ngựa.
     *
     * Ba dữ liệu phái sinh, mỗi cái một subquery tương quan:
     *   - giai đoạn hiện tại: phase của giáo án đang ACTIVE, lấy phase mới nhất
     *     đã bắt đầu
     *   - cự ly 7 ngày: tổng actual_distance các buổi đã hoàn thành
     *   - thể lực + đánh giá: buổi tập hoàn thành gần nhất
     *
     * OUTER APPLY thay vì LEFT JOIN cho buổi gần nhất: nó cho phép TOP 1 bên
     * trong, nếu không sẽ phải viết window function rồi lọc lại.
     */
    private List<TrainerDashboard.HorseRow> loadHorses() {
        String sql = """
                SELECT
                    h.horse_id,
                    h.horse_name,
                    h.registration_code,
                    h.status,
                    CASE WHEN lk.lock_id IS NULL THEN 0 ELSE 1 END AS is_locked,
                    ph.phase_label,
                    ISNULL(wk.distance_m, 0) / 1000.0 AS weekly_distance_km,
                    last_s.fitness_index,
                    last_s.evaluation
                FROM HORSE h

                OUTER APPLY (
                    SELECT TOP 1 lock_id
                    FROM TRAINING_LOCK
                    WHERE horse_id = h.horse_id AND status = 'ACTIVE'
                ) lk

                OUTER APPLY (
                    SELECT TOP 1
                        CONCAT('Phase ', p.phase_no, ' - ', p.phase_name) AS phase_label
                    FROM PLAN_PHASE p
                    JOIN TRAINING_PLAN tp ON tp.plan_id = p.plan_id
                    WHERE tp.horse_id = h.horse_id
                      AND tp.status = 'ACTIVE'
                      AND (p.start_date IS NULL OR p.start_date <= CAST(GETDATE() AS DATE))
                    ORDER BY p.phase_no DESC
                ) ph

                OUTER APPLY (
                    SELECT SUM(s.actual_distance) AS distance_m
                    FROM TRAINING_SESSION s
                    WHERE s.horse_id = h.horse_id
                      AND s.status IN ('COMPLETED', 'STOPPED_EARLY')
                      AND s.session_date >= DATEADD(DAY, -7, GETDATE())
                ) wk

                OUTER APPLY (
                    SELECT TOP 1
                        %s AS fitness_index,
                        s.evaluation
                    FROM TRAINING_SESSION s
                    WHERE s.horse_id = h.horse_id
                      AND s.status IN ('COMPLETED', 'STOPPED_EARLY')
                    ORDER BY s.session_date DESC
                ) last_s

                ORDER BY h.horse_name
                """.formatted(FITNESS_EXPR);

        return jdbc.query(sql, (rs, n) -> {
            // getObject chu khong phai getInt + wasNull(): wasNull() noi ve cot
            // DOC GAN NHAT, nen chen mot lenh doc khac vao giua la no tra loi
            // sai ve cot khac. getObject tra thang null, khong co bay do.
            Integer fitness = (Integer) rs.getObject("fitness_index");
            BigDecimal km = rs.getBigDecimal("weekly_distance_km");
            return new TrainerDashboard.HorseRow(
                    rs.getInt("horse_id"),
                    rs.getString("horse_name"),
                    rs.getString("registration_code"),
                    rs.getString("phase_label"),
                    km == null ? BigDecimal.ZERO : km.setScale(1, RoundingMode.HALF_UP),
                    fitness,
                    rs.getString("evaluation"),
                    rs.getString("status"),
                    rs.getInt("is_locked") == 1);
        });
    }

    private List<TrainerDashboard.AlertRow> loadOpenAlerts() {
        String sql = """
                SELECT TOP 10
                    a.alert_id, h.horse_name, a.metric_type,
                    a.actual_value, a.threshold_value, a.severity, a.triggered_at
                FROM THRESHOLD_ALERT a
                JOIN TRAINING_SESSION s ON s.session_id = a.session_id
                JOIN HORSE h           ON h.horse_id   = s.horse_id
                WHERE a.resolved_by IS NULL
                ORDER BY
                    CASE a.severity WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END,
                    a.triggered_at DESC
                """;
        return jdbc.query(sql, (rs, n) -> {
            Timestamp ts = rs.getTimestamp("triggered_at");
            return new TrainerDashboard.AlertRow(
                    rs.getInt("alert_id"),
                    rs.getString("horse_name"),
                    rs.getString("metric_type"),
                    rs.getBigDecimal("actual_value"),
                    rs.getBigDecimal("threshold_value"),
                    rs.getString("severity"),
                    ts == null ? null : ts.toLocalDateTime());
        });
    }
}
