package racehorse.backend.race;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Flow 5 - đăng ký giải đua và kết quả. Một dòng RACE_REGISTRATION (V1) giữ cả
 * đăng ký lẫn kết quả; tập giá trị result chốt ở V17.
 */
public final class RaceModels {

    private RaceModels() {
    }

    /**
     * state tính từ result và ngày đua:
     *  UPCOMING        - chưa tới ngày đua
     *  AWAITING_RESULT - đã qua ngày đua mà chưa nhập kết quả
     *  FINISHED        - đã có WON / PLACED / UNPLACED
     *  SCRATCHED       - rút khỏi giải trước ngày đua
     */
    public record Entry(
            int registrationId, int horseId, String horseName, String stallNo,
            int ownerId, String ownerName, String raceName, LocalDate raceDate,
            int registeredBy, String registeredByName,
            String result, BigDecimal prizeMoney, String note, String state) {
    }

    public record RegisterRequest(
            @NotNull(message = "Horse is required") Integer horseId,
            @NotBlank(message = "Race name is required")
            @Size(max = 150, message = "Race name must be at most 150 characters") String raceName,
            @NotNull(message = "Race date is required") LocalDate raceDate,
            @Size(max = 255, message = "Note must be at most 255 characters") String note) {
    }

    public record ResultRequest(
            @NotBlank(message = "Result is required")
            @Pattern(regexp = "WON|PLACED|UNPLACED", message = "Result must be WON, PLACED or UNPLACED")
            String result,
            @DecimalMin(value = "0", message = "Prize money cannot be negative") BigDecimal prizeMoney,
            @Size(max = 255, message = "Note must be at most 255 characters") String note) {
    }

    public record ScratchRequest(
            @NotBlank(message = "Reason is required")
            @Size(max = 200, message = "Reason must be at most 200 characters") String reason) {
    }

    /** Ngựa có thể đăng ký: problem = null nghĩa là đăng ký được. */
    public record Candidate(int horseId, String horseName, String stallNo, String ownerName,
                            Integer age, String healthStatus, String problem) {
    }

    public record HorseSummary(int horseId, String horseName, String ownerName,
                               int entries, int wins, int places, BigDecimal prizeMoney) {
    }

    /** Thành tích cả câu lạc bộ trong một năm; doanh thu giải đua = tổng tiền thưởng. */
    public record Summary(int year, int entries, int finished, int wins, int places, int unplaced,
                          int scratched, int upcoming, BigDecimal prizeMoney, List<HorseSummary> horses) {
    }
}
