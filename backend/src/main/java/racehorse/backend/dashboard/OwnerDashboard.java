package racehorse.backend.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Kiểu dữ liệu trả về cho màn tổng quan của Horse Owner.
 *
 * Chỉ gồm ngựa ACADEMY của chủ đang đăng nhập: hồ sơ PEDIGREE không tập,
 * không tốn chi phí nuôi, nên không có gì để hiện trên màn này.
 */
public final class OwnerDashboard {

    private OwnerDashboard() {
    }

    public record Response(
            Summary summary,
            List<HorseCard> horses,
            Costs costs) {
    }

    /** Bốn ô số liệu trên đầu màn hình. */
    public record Summary(
            int horseCount,
            Integer avgFitness,             // null nếu chưa con nào có buổi tập
            BigDecimal weeklyDistanceKm,
            NextRace nextRace) {            // null nếu chưa đăng ký giải nào sắp tới
    }

    public record NextRace(
            int horseId,
            String horseName,
            String raceName,
            LocalDate raceDate) {
    }

    /** Một con ngựa, đủ để vẽ khối tổng quan, biểu đồ thể lực và nhận xét. */
    public record HorseCard(
            int horseId,
            String horseName,
            String registrationCode,
            String photoUrl,                // null nếu chủ chưa tải ảnh
            String breed,
            LocalDate dateOfBirth,
            BigDecimal weight,
            String status,
            boolean locked,
            Integer fitness,                // chỉ số tuần gần nhất, null nếu chưa có
            BigDecimal weeklyDistanceKm,
            NextRace nextRace,
            Stats stats,
            List<FitnessPoint> fitnessTrend,
            List<Remark> remarks) {
    }

    /**
     * Năm chỉ số huấn luyện, thang 0-100, tính từ các buổi tập trong
     * OwnerDashboardService.STATS_DAYS ngày gần nhất. Null khi chưa có buổi nào
     * có số liệu tương ứng. Công thức xem OwnerDashboardService.loadStats.
     */
    public record Stats(
            int sessionCount,
            Integer speed,
            Integer stamina,
            Integer power,
            Integer guts,
            Integer wit) {
    }

    public record FitnessPoint(
            LocalDate weekStart,
            String label,
            int fitnessIndex) {
    }

    /** Một buổi tập đã được Head Trainer đánh giá (UC12). */
    public record Remark(
            int sessionId,
            LocalDateTime sessionDate,
            String phaseName,
            Integer actualDistance,
            String status,
            String evaluation,
            String remarks) {
    }

    /**
     * Bảng chi phí và tiền thưởng. Mỗi dòng có amounts xếp đúng thứ tự cột
     * trong horses, cộng thêm total ở cuối.
     * category: FEEDING, MEDICAL, TRAINING, OTHER (EXPENSE_LOG) và PRIZE_MONEY
     * (RACE_REGISTRATION).
     */
    public record Costs(
            String period,
            LocalDate from,
            LocalDate to,
            List<CostColumn> horses,
            List<CostRow> rows) {
    }

    public record CostColumn(
            int horseId,
            String horseName) {
    }

    public record CostRow(
            String category,
            List<BigDecimal> amounts,
            BigDecimal total) {
    }
}
