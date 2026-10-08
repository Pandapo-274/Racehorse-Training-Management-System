package racehorse.backend.horse;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** status KHÔNG nằm ở đây: trạng thái do bác sĩ thú y quyết định (UC14, UC18). */
public record HorseRequest(
        @NotNull(message = "Chủ ngựa là bắt buộc") Integer ownerId,
        Integer sireId,
        Integer damId,
        @NotBlank(message = "Tên ngựa là bắt buộc") @Size(max = 100) String horseName,
        @NotBlank(message = "Mã đăng ký là bắt buộc") @Size(max = 30) String registrationCode,
        @Size(max = 50) String breed,
        @Pattern(regexp = "COLT|FILLY|STALLION|MARE|GELDING",
                 message = "Giới tính phải là COLT, FILLY, STALLION, MARE hoặc GELDING") String horseGender,
        @PastOrPresent(message = "Ngày sinh không được ở tương lai") LocalDate dateOfBirth,
        @Size(max = 50) String color,
        @DecimalMin(value = "0.0", inclusive = false, message = "Cân nặng phải lớn hơn 0") BigDecimal weight,
        @Size(max = 20) String stallNo
) {}