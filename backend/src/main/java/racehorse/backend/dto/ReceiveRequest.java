package racehorse.backend.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Manager nhận ngựa vào CLB sau khi chủ đã ký và trả tiền (V49).
 * Đây là lúc cấp mã đăng ký, xếp chuồng, chốt HLV và chấm BCS - phần việc mà
 * nút Approve cũ làm ngay lúc duyệt. weight / heightCm: cân đo lại khi ngựa tới;
 * bỏ trống thì giữ số chủ ngựa đã khai.
 */
public record ReceiveRequest(
        @Size(max = 30, message = "Registration code must be at most 30 characters")
        String registrationCode,

        @Size(max = 20, message = "Stall must be at most 20 characters")
        String stallNo,

        Integer trainerId,

        @NotNull(message = "Score the body condition (1-9)")
        @Min(value = 1, message = "Body condition score is 1 to 9")
        @Max(value = 9, message = "Body condition score is 1 to 9")
        Integer bcs,

        @DecimalMin(value = "30", message = "Weight must be at least 30 kg")
        @DecimalMax(value = "1000", message = "Weight must be at most 1000 kg")
        BigDecimal weight,

        @DecimalMin(value = "60", message = "Height must be at least 60 cm")
        @DecimalMax(value = "200", message = "Height must be at most 200 cm")
        BigDecimal heightCm,

        @Size(max = 255, message = "Note must be at most 255 characters")
        String note
) {}
