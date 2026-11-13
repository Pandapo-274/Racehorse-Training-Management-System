package racehorse.backend.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDateTime;

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
        Integer plannedDuration,
        @DecimalMin(value = "1.0", message = "Max allowed speed must be between 1 and 30 m/s")
        @DecimalMax(value = "30.0", message = "Max allowed speed must be between 1 and 30 m/s")
        BigDecimal velocityCeiling,
        String remarks
) {
    public TrainingSessionRequest(Integer phaseId, Integer assignedTo,
                                  LocalDateTime sessionDate, Integer plannedDistance) {
        this(phaseId, assignedTo, sessionDate, plannedDistance, 60, null, null);
    }

    public TrainingSessionRequest(Integer phaseId, Integer assignedTo,
                                  LocalDateTime sessionDate, Integer plannedDistance,
                                  Integer plannedDuration) {
        this(phaseId, assignedTo, sessionDate, plannedDistance, plannedDuration, null, null);
    }
}
