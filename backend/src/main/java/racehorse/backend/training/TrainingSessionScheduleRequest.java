package racehorse.backend.training;

import java.time.LocalDateTime;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record TrainingSessionScheduleRequest(
        @NotNull(message = "Groom is required")
        Integer assignedTo,

        @NotNull(message = "Session time is required")
        LocalDateTime sessionDate,

        @Min(value = 15, message = "Duration must be at least 15 minutes")
        @Max(value = 720, message = "Duration cannot exceed 12 hours (720 minutes)")
        Integer plannedDuration
) {
    public TrainingSessionScheduleRequest(Integer assignedTo, LocalDateTime sessionDate) {
        this(assignedTo, sessionDate, null);
    }
}
