package racehorse.backend.training;

import java.time.LocalDateTime;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * horse_id KHÔNG nằm ở đây: service lấy từ phase, không cho client nhập tay.
 * V37: plannedDuration là thời lượng huấn luyện dự kiến (phút), tối đa 12 tiếng (720 phút),
 * mặc định 60 phút nếu không truyền.
 */
public record TrainingSessionRequest(
        @NotNull(message = "Phase is required") Integer phaseId,
        @NotNull(message = "Assignee is required") Integer assignedTo,
        @NotNull(message = "Session time is required") LocalDateTime sessionDate,
        @Positive(message = "Planned distance must be greater than 0 (meters)")
        @Max(value = 5000, message = "Planned distance cannot exceed 5,000 meters") Integer plannedDistance,
        @NotNull(message = "Planned duration is required")
        @Min(value = 15, message = "Duration must be at least 15 minutes")
        @Max(value = 720, message = "Duration cannot exceed 12 hours (720 minutes)")
        Integer plannedDuration
) {
    public TrainingSessionRequest(Integer phaseId, Integer assignedTo,
                                  LocalDateTime sessionDate, Integer plannedDistance) {
        this(phaseId, assignedTo, sessionDate, plannedDistance, 60);
    }
}
