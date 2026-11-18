package racehorse.backend.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Một lần đo từ cảm biến/nhân viên trong lúc ngựa đang chạy. */
// Body của POST /api/sessions/{sessionId}/vitals: Groom (hoặc HLV) gửi nhịp tim và/hoặc vận
// tốc trong lúc buổi tập RUNNING. AlertService so với ngưỡng và tạo THRESHOLD_ALERT nếu vượt.
public record VitalsRequest(
        @Min(value = 20, message = "Heart rate must be between 20 and 300")
        @Max(value = 300, message = "Heart rate must be between 20 and 300")
        Integer heartRate,

        @DecimalMin(value = "0.0", message = "Velocity cannot be negative")
        @DecimalMax(value = "99.99", message = "Velocity is too large")
        BigDecimal velocity
) {}
