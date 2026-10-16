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
        @NotNull(message = "Owner is required") Integer ownerId,
        Integer sireId,
        Integer damId,
        @NotBlank(message = "Horse name is required") @Size(max = 100) String horseName,
        @NotBlank(message = "Registration code is required") @Size(max = 30) String registrationCode,
        @Size(max = 50) String breed,
        @Pattern(regexp = "COLT|FILLY|STALLION|MARE|GELDING",
                 message = "Gender must be COLT, FILLY, STALLION, MARE or GELDING") String horseGender,
        @PastOrPresent(message = "Date of birth must not be in the future") LocalDate dateOfBirth,
        @Size(max = 50) String color,
        @DecimalMin(value = "0.0", inclusive = false, message = "Weight must be greater than 0") BigDecimal weight,
        @Size(max = 20) String stallNo
) {}