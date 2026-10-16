package racehorse.backend.training;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** horse_id KHÔNG nằm ở đây: service lấy từ phase, không cho client nhập tay. */
public record TrainingSessionRequest(
        @NotNull(message = "Phase is required") Integer phaseId,
        @NotNull(message = "Assignee is required") Integer assignedTo,
        @NotNull(message = "Session time is required") LocalDateTime sessionDate,
        @Positive(message = "Planned distance must be greater than 0 (meters)") Integer plannedDistance
) {}