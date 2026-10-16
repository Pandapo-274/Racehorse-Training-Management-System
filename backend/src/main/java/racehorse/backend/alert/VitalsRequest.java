package racehorse.backend.alert;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Một lần đo từ cảm biến/nhân viên trong lúc ngựa đang chạy. */
public record VitalsRequest(
        @NotNull(message = "Heart rate is required")
        @Min(value = 20, message = "Heart rate must be between 20 and 300")
        @Max(value = 300, message = "Heart rate must be between 20 and 300")
        Integer heartRate,

        @DecimalMin(value = "0.0", message = "Velocity cannot be negative")
        @DecimalMax(value = "99.99", message = "Velocity is too large")
        BigDecimal velocity
) {}
