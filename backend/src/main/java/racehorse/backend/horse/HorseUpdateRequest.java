package racehorse.backend.horse;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Những gì CHỦ NGỰA được sửa trên ngựa của mình.
 *
 * Cố ý KHÔNG có: ownerId, registrationCode, stallNo, weight, recordType, status.
 *  - registrationCode và stallNo là định danh của ngựa trong hệ thống: cấp một
 *    lần lúc duyệt, sau đó cố định.
 *  - ownerId: không tự chuyển nhượng ngựa.
 *  - weight: do học viện cân.
 *  - status: do bác sĩ thú y quyết định.
 * Vì record không có các trường này nên client có gửi lên cũng bị bỏ qua.
 */
public record HorseUpdateRequest(
        @NotBlank(message = "Name is required") @Size(max = 100) String horseName,
        @Size(max = 50) String breed,
        @Pattern(regexp = "COLT|FILLY|STALLION|MARE|GELDING",
                 message = "Sex must be COLT, FILLY, STALLION, MARE or GELDING") String horseGender,
        @PastOrPresent(message = "Date of birth cannot be in the future") LocalDate dateOfBirth,
        @Size(max = 50) String color,
        Integer sireId,
        Integer damId
) {}