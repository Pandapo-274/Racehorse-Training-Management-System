package racehorse.backend.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import racehorse.backend.auth.ApiException;

/**
 * Truy vấn dữ liệu cho màn tổng quan của Horse Owner.
 *
 * Mọi con số đều tính từ dữ liệu thật: TRAINING_SESSION cho thể lực và chỉ số
 * huấn luyện, RACE_REGISTRATION cho giải sắp tới và tiền thưởng, EXPENSE_LOG
 * cho chi phí. Chỉ số thể lực dùng chung FITNESS_EXPR với TrainerDashboardService.
 *
 * Mỗi chủ ngựa chỉ có vài con nên query theo từng con cho dễ đọc, thay vì gộp
 * thành một câu SQL lớn.
 */
@Service
public class OwnerDashboardService {

    static final String DEFAULT_PERIOD = "LAST_90_DAYS";
    private static final Set<String> PERIODS = Set.of("LAST_90_DAYS", "THIS_YEAR", "ALL");

    /** Thứ tự dòng trong bảng chi phí. PRIZE_MONEY luôn ở cuối. */
    private static final List<String> COST_CATEGORIES =
            List.of("FEEDING", "MEDICAL", "TRAINING", "OTHER", "PRIZE_MONEY");

    private static final int TREND_WEEKS = 8;
    /** Cửa sổ tính năm chỉ số huấn luyện. */
    static final int STATS_DAYS = 28;
    /** Vận tốc đỉnh (m/s) ứng với 100 điểm Speed. Ngựa đua thuần chủng chạy nước rút khoảng 18 m/s. */
    private static final double SPEED_FULL_MARK = 18.0;
    private static final int REMARK_COUNT = 3;

    private static final DateTimeFormatter WEEK_LABEL = DateTimeFormatter.ofPattern("dd/MM");

    private final JdbcTemplate jdbc;

    public OwnerDashboardService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public OwnerDashboard.Response load(int ownerId, String period) {
        String safePeriod = period == null ? DEFAULT_PERIOD : period.trim().toUpperCase();
        if (!PERIODS.contains(safePeriod)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid period",
                    Map.of("period", "Must be one of " + PERIODS));
        }

        List<OwnerDashboard.HorseCard> horses = loadHorses(ownerId);
        return new OwnerDashboard.Response(
                summarize(horses),
                horses,
                loadCosts(ownerId, horses, safePeriod));
    }

    /* ------------------------------------------------------------------ */

    private OwnerDashboard.Summary summarize(List<OwnerDashboard.HorseCard> horses) {
        Integer avgFitness = (int) Math.round(horses.stream()
                .map(OwnerDashboard.HorseCard::fitness)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .average()
                .orElse(-1));
        BigDecimal km = horses.stream()
                .map(OwnerDashboard.HorseCard::weeklyDistanceKm)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        OwnerDashboard.NextRace next = horses.stream()
                .map(OwnerDashboard.HorseCard::nextRace)
                .filter(Objects::nonNull)
                .min(Comparator.comparing(OwnerDashboard.NextRace::raceDate))
                .orElse(null);
        return new OwnerDashboard.Summary(horses.size(), avgFitness < 0 ? null : avgFitness, km, next);
    }

    /**
     * Ngựa ACADEMY của chủ, kèm khoá tập, cự ly 7 ngày và giải sắp tới.
     * OUTER APPLY cùng cách TrainerDashboardService.loadHorses dùng.
     */
    private List<OwnerDashboard.HorseCard> loadHorses(int ownerId) {
        String sql = """
                SELECT
                    h.horse_id, h.horse_name, h.registration_code, h.photo_url, h.breed,
                    h.date_of_birth, h.weight, h.status,
                    CASE WHEN lk.lock_id IS NULL THEN 0 ELSE 1 END AS is_locked,
                    ISNULL(wk.distance_m, 0) / 1000.0 AS weekly_distance_km,
                    nr.race_name, nr.race_date
                FROM HORSE h

                OUTER APPLY (
                    SELECT TOP 1 lock_id
                    FROM TRAINING_LOCK
                    WHERE horse_id = h.horse_id AND status = 'ACTIVE'
                ) lk

                OUTER APPLY (
                    SELECT SUM(s.actual_distance) AS distance_m
                    FROM TRAINING_SESSION s
                    WHERE s.horse_id = h.horse_id
                      AND s.status IN ('COMPLETED', 'STOPPED_EARLY')
                      AND s.session_date >= DATEADD(DAY, -7, GETDATE())
                ) wk

                OUTER APPLY (
                    SELECT TOP 1 r.race_name, r.race_date
                    FROM RACE_REGISTRATION r
                    WHERE r.horse_id = h.horse_id
                      AND r.result IS NULL
                      AND r.race_date >= CAST(GETDATE() AS DATE)
                    ORDER BY r.race_date
                ) nr

                WHERE h.owner_id = ? AND h.record_type = 'ACADEMY'
                ORDER BY h.horse_name
                """;

        // Đọc hết danh sách trước rồi mới query tiếp cho từng con: query lồng
        // trong RowMapper sẽ giữ hai kết nối cùng lúc.
        List<OwnerDashboard.HorseCard> base = jdbc.query(sql, (rs, n) -> {
            int horseId = rs.getInt("horse_id");
            String horseName = rs.getString("horse_name");
            BigDecimal km = rs.getBigDecimal("weekly_distance_km");
            String raceName = rs.getString("race_name");
            OwnerDashboard.NextRace nextRace = raceName == null ? null
                    : new OwnerDashboard.NextRace(horseId, horseName, raceName,
                            rs.getObject("race_date", LocalDate.class));

            return new OwnerDashboard.HorseCard(
                    horseId,
                    horseName,
                    rs.getString("registration_code"),
                    rs.getString("photo_url"),
                    rs.getString("breed"),
                    rs.getObject("date_of_birth", LocalDate.class),
                    rs.getBigDecimal("weight"),
                    rs.getString("status"),
                    rs.getInt("is_locked") == 1,
                    null,
                    km == null ? BigDecimal.ZERO : km.setScale(1, RoundingMode.HALF_UP),
                    nextRace,
                    null, List.of(), List.of());
        }, ownerId);

        return base.stream().map(h -> {
            List<OwnerDashboard.FitnessPoint> trend = loadFitnessTrend(h.horseId());
            Integer fitness = trend.isEmpty() ? null : trend.get(trend.size() - 1).fitnessIndex();
            return new OwnerDashboard.HorseCard(
                    h.horseId(), h.horseName(), h.registrationCode(), h.photoUrl(), h.breed(),
                    h.dateOfBirth(), h.weight(), h.status(), h.locked(),
                    fitness, h.weeklyDistanceKm(), h.nextRace(),
                    loadStats(h.horseId()), trend, loadRemarks(h.horseId()));
        }).toList();
    }

    /** Giống TrainerDashboardService.loadFitnessTrend nhưng cho một con ngựa. */
    private List<OwnerDashboard.FitnessPoint> loadFitnessTrend(int horseId) {
        String sql = """
                SELECT
                    DATEADD(WEEK, DATEDIFF(WEEK, 0, s.session_date), 0) AS week_start,
                    AVG(CAST(%s AS FLOAT))                              AS avg_fitness
                FROM TRAINING_SESSION s
                WHERE s.horse_id = ?
                  AND s.status IN ('COMPLETED', 'STOPPED_EARLY')
                  AND s.recovery_heart_rate IS NOT NULL
                  AND s.session_date >= DATEADD(WEEK, ?, CAST(GETDATE() AS DATE))
                GROUP BY DATEADD(WEEK, DATEDIFF(WEEK, 0, s.session_date), 0)
                ORDER BY week_start
                """.formatted(TrainerDashboardService.FITNESS_EXPR);

        return jdbc.query(sql, (rs, n) -> {
            LocalDate weekStart = rs.getTimestamp("week_start").toLocalDateTime().toLocalDate();
            return new OwnerDashboard.FitnessPoint(
                    weekStart,
                    weekStart.format(WEEK_LABEL),
                    (int) Math.round(rs.getDouble("avg_fitness")));
        }, horseId, -TREND_WEEKS);
    }

    /**
     * Năm chỉ số huấn luyện, thang 0-100. Schema không lưu sẵn, nên mỗi chỉ số
     * gắn với một số đo thật của buổi tập:
     *   Speed   - vận tốc đỉnh trung bình, SPEED_FULL_MARK m/s = 100.
     *   Stamina - nhịp tim hồi phục (FITNESS_EXPR, cùng thang với thể lực).
     *   Power   - cự ly thực tế so với cự ly giáo án đặt ra.
     *   Guts    - tỉ lệ buổi chạy hết, không bị dừng sớm.
     *   Wit     - đánh giá của Head Trainer: POOR 25, FAIR 50, GOOD 75, EXCELLENT 100.
     * Muốn đổi cách tính thì sửa ở đây, frontend chỉ hiển thị.
     */
    private OwnerDashboard.Stats loadStats(int horseId) {
        String sql = """
                SELECT
                    COUNT(*) AS session_count,
                    AVG(CAST(s.peak_velocity AS FLOAT)) AS avg_velocity,
                    AVG(CAST(%s AS FLOAT)) AS avg_recovery,
                    AVG(CASE WHEN s.planned_distance > 0 AND s.actual_distance IS NOT NULL
                             THEN s.actual_distance * 100.0 / s.planned_distance END) AS completion,
                    AVG(CASE WHEN s.status = 'COMPLETED' THEN 100.0 ELSE 0.0 END) AS finished,
                    AVG(CASE s.evaluation
                            WHEN 'EXCELLENT' THEN 100.0
                            WHEN 'GOOD'      THEN 75.0
                            WHEN 'FAIR'      THEN 50.0
                            WHEN 'POOR'      THEN 25.0
                        END) AS evaluation_score
                FROM TRAINING_SESSION s
                WHERE s.horse_id = ?
                  AND s.status IN ('COMPLETED', 'STOPPED_EARLY')
                  AND s.session_date >= DATEADD(DAY, ?, GETDATE())
                """.formatted(TrainerDashboardService.FITNESS_EXPR);

        return jdbc.queryForObject(sql, (rs, n) -> {
            Double velocity = toDouble(rs.getObject("avg_velocity"));
            return new OwnerDashboard.Stats(
                    rs.getInt("session_count"),
                    score(velocity == null ? null : velocity * 100.0 / SPEED_FULL_MARK),
                    score(toDouble(rs.getObject("avg_recovery"))),
                    score(toDouble(rs.getObject("completion"))),
                    score(toDouble(rs.getObject("finished"))),
                    score(toDouble(rs.getObject("evaluation_score"))));
        }, horseId, -STATS_DAYS);
    }

    /** Các buổi đã được đánh giá gần nhất, mới nhất trước. */
    private List<OwnerDashboard.Remark> loadRemarks(int horseId) {
        String sql = """
                SELECT TOP (?) s.session_id, s.session_date, p.phase_name,
                       s.actual_distance, s.status, s.evaluation, s.remarks
                FROM TRAINING_SESSION s
                JOIN PLAN_PHASE p ON p.phase_id = s.phase_id
                WHERE s.horse_id = ? AND s.evaluation IS NOT NULL
                ORDER BY s.session_date DESC
                """;
        return jdbc.query(sql, (rs, n) -> new OwnerDashboard.Remark(
                rs.getInt("session_id"),
                rs.getObject("session_date", LocalDateTime.class),
                rs.getString("phase_name"),
                (Integer) rs.getObject("actual_distance"),
                rs.getString("status"),
                rs.getString("evaluation"),
                rs.getString("remarks")), REMARK_COUNT, horseId);
    }

    /**
     * Chi phí (EXPENSE_LOG) và tiền thưởng (RACE_REGISTRATION) theo từng con.
     * Dòng nào không có số liệu vẫn hiện, giá trị 0, để bảng luôn đủ hạng mục.
     */
    private OwnerDashboard.Costs loadCosts(int ownerId, List<OwnerDashboard.HorseCard> horses, String period) {
        LocalDate today = LocalDate.now();
        LocalDate from = switch (period) {
            case "THIS_YEAR" -> today.withDayOfYear(1);
            case "ALL" -> null;
            default -> today.minusDays(89);
        };
        // ALL: không lọc ngày. Dùng biên rất rộng thay vì ghép SQL hai kiểu.
        Date sqlFrom = Date.valueOf(from == null ? LocalDate.of(1900, 1, 1) : from);
        Date sqlTo = Date.valueOf(today);

        String sql = """
                SELECT e.horse_id, e.category, SUM(e.amount) AS amount
                FROM EXPENSE_LOG e
                JOIN HORSE h ON h.horse_id = e.horse_id
                WHERE h.owner_id = ? AND h.record_type = 'ACADEMY'
                  AND e.expense_date BETWEEN ? AND ?
                GROUP BY e.horse_id, e.category

                UNION ALL

                SELECT r.horse_id, 'PRIZE_MONEY', SUM(r.prize_money)
                FROM RACE_REGISTRATION r
                JOIN HORSE h ON h.horse_id = r.horse_id
                WHERE h.owner_id = ? AND h.record_type = 'ACADEMY'
                  AND r.prize_money IS NOT NULL
                  AND r.race_date BETWEEN ? AND ?
                GROUP BY r.horse_id
                """;

        // category -> horseId -> số tiền
        Map<String, Map<Integer, BigDecimal>> matrix = new HashMap<>();
        jdbc.query(sql, rs -> {
            matrix.computeIfAbsent(rs.getString("category"), k -> new HashMap<>())
                    .put(rs.getInt("horse_id"), rs.getBigDecimal("amount"));
        }, ownerId, sqlFrom, sqlTo, ownerId, sqlFrom, sqlTo);

        List<OwnerDashboard.CostColumn> columns = horses.stream()
                .map(h -> new OwnerDashboard.CostColumn(h.horseId(), h.horseName()))
                .toList();

        List<OwnerDashboard.CostRow> rows = new ArrayList<>();
        for (String category : COST_CATEGORIES) {
            Map<Integer, BigDecimal> byHorse = matrix.getOrDefault(category, Map.of());
            List<BigDecimal> amounts = columns.stream()
                    .map(c -> byHorse.getOrDefault(c.horseId(), BigDecimal.ZERO))
                    .toList();
            BigDecimal total = amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            rows.add(new OwnerDashboard.CostRow(category, amounts, total));
        }

        return new OwnerDashboard.Costs(period, from, today, columns, rows);
    }

    /* ---------- helper ---------- */

    /** Làm tròn và cắt vào [0, 100]. Null giữ nguyên: chưa có dữ liệu khác với điểm 0. */
    private static Integer score(Double value) {
        if (value == null) return null;
        return (int) Math.round(Math.max(0, Math.min(100, value)));
    }

    /** AVG trên cột DECIMAL trả BigDecimal, trên FLOAT trả Double. */
    private static Double toDouble(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }
}
