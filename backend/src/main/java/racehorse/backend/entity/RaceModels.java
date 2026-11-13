package racehorse.backend.entity;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import racehorse.backend.dto.RegisterRequest;
import racehorse.backend.service.RaceService;

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
            String result, BigDecimal prizeMoney, String note, String state,
            /* V22 */ int raceId, Integer distanceM, String venue, String raceStatus,
            Integer finishPosition, Integer finishTimeMs,
            /* V23 */ String scratchReason,
            /* V27 */ String approvalStatus, String rejectReason) {
    }

    public record RejectRequest(
            @NotBlank(message = "Reason is required")
            @Size(max = 255, message = "Reason must be at most 255 characters")
            String reason) {
    }

    /**
     * A race (V22). entries = horses entered and not scratched.
     * Name and date cannot change after creation: RACE_REGISTRATION keeps a copy
     * of both, and a race that moved would leave the copies wrong.
     */
    public record RaceEvent(int raceId, String raceName, LocalDate raceDate, Integer distanceM, String venue,
                            LocalDate entryDeadline, BigDecimal entryFee, Integer maxEntries,
                            String status, String cancelReason, int entries) {
    }

    public record RaceEventRequest(
            @NotBlank(message = "Race name is required")
            @Size(max = 150, message = "Race name must be at most 150 characters") String raceName,
            @NotNull(message = "Race date is required") LocalDate raceDate,
            @NotNull(message = "Distance is required")
            @Min(value = 100, message = "Distance must be at least 100 m")
            @Max(value = 10000, message = "Distance must be at most 10000 m") Integer distanceM,
            @NotBlank(message = "Venue is required")
            @Size(max = 150, message = "Venue must be at most 150 characters") String venue,
            @NotNull(message = "Entry deadline is required") LocalDate entryDeadline,
            @NotNull(message = "Entry fee is required")
            @DecimalMin(value = "0", message = "Entry fee cannot be negative") BigDecimal entryFee,
            @NotNull(message = "Max horses is required")
            @Min(value = 1, message = "At least 1 horse")
            @Max(value = 30, message = "At most 30 horses") Integer maxEntries) {
    }

    public record CancelRaceRequest(
            @NotBlank(message = "Reason is required")
            @Size(max = 255, message = "Reason must be at most 255 characters") String reason) {
    }

    /** Enter a horse in an existing race (V22: by raceId, not by typed name and date). */
    public record RegisterRequest(
            @NotNull(message = "Horse is required") Integer horseId,
            @NotNull(message = "Race is required") Integer raceId,
            @Size(max = 255, message = "Note must be at most 255 characters") String note) {
    }

    /**
     * finishPosition / finishTimeMs are optional (V22). When a position is
     * given it must agree with result: WON = 1, PLACED = 2-3, UNPLACED = 4+.
     * reason: required only when the Club Manager changes a result after the
     * edit window has closed (#5).
     */
    public record ResultRequest(
            @NotBlank(message = "Result is required")
            @Pattern(regexp = "WON|PLACED|UNPLACED", message = "Result must be WON, PLACED or UNPLACED")
            String result,
            @DecimalMin(value = "0", message = "Prize money cannot be negative") BigDecimal prizeMoney,
            @Size(max = 255, message = "Note must be at most 255 characters") String note,
            @Min(value = 1, message = "Position must be 1 or more")
            @Max(value = 30, message = "Position must be at most 30") Integer finishPosition,
            @Min(value = 1, message = "Time must be greater than 0") Integer finishTimeMs,
            @Size(max = 255, message = "Reason must be at most 255 characters") String reason) {
    }

    public record ScratchRequest(
            @NotBlank(message = "Reason is required")
            @Size(max = 500, message = "Reason must be at most 500 characters") String reason) {
    }

    /**
     * Ngựa có thể đăng ký: problem = null nghĩa là đăng ký được.
     * problems: every reason that blocks (problem is the first of them).
     * warnings: do not block - e.g. no trial run recently, vaccination never recorded.
     */
    public record Candidate(int horseId, String horseName, String stallNo, String ownerName,
                            Integer age, String healthStatus, String problem,
                            List<String> problems, List<String> warnings) {
    }

    public record HorseSummary(int horseId, String horseName, String ownerName,
                               int entries, int wins, int places, BigDecimal prizeMoney) {
    }

    /** Club-wide race record for one year. See Summary on what prize money means. */
    /**
     * awaitingResult is counted apart from upcoming: a race whose date has
     * passed but has no result yet is NOT "upcoming". The switch in
     * RaceService.summary used to handle only SCRATCHED and FINISHED, so
     * AWAITING_RESULT fell into default and was counted as upcoming - the stats
     * said a race was ahead when it had been run the week before.
     *
     * prizeMoney is the TOTAL PRIZE MONEY OF THE OWNERS, not club revenue. Prize
     * money belongs to the horse's owner; nothing in the system models a
     * training fee or commission the club would earn.
     */
    public record Summary(int year, int entries, int finished, int wins, int places, int unplaced,
                          int scratched, int upcoming, int awaitingResult,
                          BigDecimal prizeMoney, List<HorseSummary> horses) {
    }
}
