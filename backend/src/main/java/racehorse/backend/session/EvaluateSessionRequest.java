package racehorse.backend.session;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Số liệu thực tế + điểm phong độ + nhận xét sau ca tập. recoveryHeartRate, peakVelocity, remarks, status là tuỳ chọn. */
public record EvaluateSessionRequest(
        @NotNull(message = "Actual distance is required")
        @Min(value = 0, message = "Actual distance cannot be negative")
        @Max(value = 100000, message = "Actual distance is too large")
        Integer actualDistance,

        @NotNull(message = "Duration is required")
        @Min(value = 1, message = "Duration must be at least 1 minute")
        @Max(value = 600, message = "Duration must be at most 600 minutes")
        Integer durationMinutes,

        @NotNull(message = "Average heart rate is required")
        @Min(value = 20, message = "Heart rate must be between 20 and 300")
        @Max(value = 300, message = "Heart rate must be between 20 and 300")
        Integer avgHeartRate,

        @NotNull(message = "Peak heart rate is required")
        @Min(value = 20, message = "Heart rate must be between 20 and 300")
        @Max(value = 300, message = "Heart rate must be between 20 and 300")
        Integer peakHeartRate,

        @Min(value = 20, message = "Heart rate must be between 20 and 300")
        @Max(value = 300, message = "Heart rate must be between 20 and 300")
        Integer recoveryHeartRate,

        @DecimalMin(value = "0.0", message = "Velocity cannot be negative")
        @DecimalMax(value = "99.99", message = "Velocity is too large")
        BigDecimal peakVelocity,

        @NotBlank(message = "Evaluation is required")
        @Pattern(regexp = "POOR|FAIR|GOOD|EXCELLENT", message = "Evaluation must be POOR, FAIR, GOOD or EXCELLENT")
        String evaluation,

        @Size(max = 2000, message = "Remarks must be at most 2000 characters")
        String remarks,

        @Pattern(regexp = "COMPLETED|STOPPED_EARLY", message = "Status must be COMPLETED or STOPPED_EARLY")
        String status
) {}
