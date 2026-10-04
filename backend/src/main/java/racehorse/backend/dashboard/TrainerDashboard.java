package racehorse.backend.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Kiểu dữ liệu trả về cho UC6 - View Master Fitness & Training Dashboard.
 *
 * Dùng record thay cho Map<String,Object> như DbTestController, vì màn này có
 * cấu trúc lồng nhiều tầng: Map lồng Map sẽ không ai biết JSON thực sự có
 * trường gì nếu không đọc hết query. Record khai báo hợp đồng ngay tại đây,
 * frontend đọc là biết.
 */
public final class TrainerDashboard {

    private TrainerDashboard() {
    }

    /** Bốn ô số liệu trên đầu màn hình. */
    public record Summary(
            int totalHorses,
            int activePlans,
            int lockedHorses,
            int openAlerts) {
    }

    /** Một điểm trên biểu đồ thể lực - mỗi điểm là một tuần. */
    public record FitnessPoint(
            LocalDate weekStart,
            String label,
            int fitnessIndex,
            int sessionCount) {
    }

    /** Một dòng trong vòng tròn trạng thái sức khỏe đàn. */
    public record StatusCount(
            String status,
            int count) {
    }

    /** Một dòng trong bảng chiến mã phụ trách. */
    public record HorseRow(
            int horseId,
            String horseName,
            String registrationCode,
            String currentPhase,
            BigDecimal weeklyDistanceKm,
            Integer fitnessIndex,
            String lastEvaluation,
            String status,
            boolean locked) {
    }

    /** Một cảnh báo vượt ngưỡng chưa xử lý. */
    public record AlertRow(
            int alertId,
            String horseName,
            String metricType,
            BigDecimal actualValue,
            BigDecimal thresholdValue,
            String severity,
            LocalDateTime triggeredAt) {
    }

    /** Toàn bộ dữ liệu một lần gọi. */
    public record Response(
            Summary summary,
            List<FitnessPoint> fitnessTrend,
            List<StatusCount> statusBreakdown,
            List<HorseRow> horses,
            List<AlertRow> openAlerts) {
    }
}
