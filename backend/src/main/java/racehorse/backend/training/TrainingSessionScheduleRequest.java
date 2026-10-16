
package racehorse.backend.training;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotNull;

public record TrainingSessionScheduleRequest(
        @NotNull(message = "Groom is required")
        Integer assignedTo,

        @NotNull(message = "Session time is required")
        LocalDateTime sessionDate
) {}
