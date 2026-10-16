package racehorse.backend.training;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record TrainingPlanRequest(
        @NotNull(message = "Horse is required") Integer horseId,
        @NotBlank(message = "Plan name is required")
        @Size(max = 150, message = "Plan name must be at most 150 characters") String planName,
        @NotNull(message = "Start date is required") LocalDate startDate,
        @NotNull(message = "End date is required") LocalDate endDate,
        @NotEmpty(message = "A plan needs at least one phase")
        @Size(max = 12, message = "At most 12 phases")
        @Valid List<@NotNull(message = "Phase must not be null") PhaseRequest> phases
) {

    /** phase_no không nằm ở đây: service tự đánh số 1, 2, 3 theo thứ tự trong danh sách. */
    public record PhaseRequest(
            @NotBlank(message = "Phase name is required")
            @Size(max = 100, message = "Phase name must be at most 100 characters") String phaseName,
            @Positive(message = "Distance must be greater than 0 (meters)") Integer distance,
            @Size(max = 50, message = "Workload must be at most 50 characters") String workload,
            @Pattern(regexp = "TURF|DIRT|SYNTHETIC",
                     message = "Surface must be TURF, DIRT or SYNTHETIC") String surface,
            @Min(value = 1, message = "At least 1 session per week")
            @Max(value = 14, message = "At most 14 sessions per week") Integer sessionsPerWeek,
            @Min(value = 100, message = "Heart rate ceiling must be between 100 and 260")
            @Max(value = 260, message = "Heart rate ceiling must be between 100 and 260") Integer heartRateCeiling,
            @NotNull(message = "Phase start date is required") LocalDate startDate,
            @NotNull(message = "Phase end date is required") LocalDate endDate
    ) {}

    public record StatusChange(
            @NotBlank(message = "Status is required")
            @Pattern(regexp = "DRAFT|ACTIVE|PAUSED|COMPLETED",
                     message = "Status must be DRAFT, ACTIVE, PAUSED or COMPLETED") String status
    ) {}
}