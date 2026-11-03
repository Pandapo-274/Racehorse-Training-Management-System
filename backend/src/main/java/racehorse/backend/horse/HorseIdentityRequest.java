package racehorse.backend.horse;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Club Manager sửa hai định danh do câu lạc bộ cấp: mã đăng ký và số chuồng.
 * stallNo để trống với hồ sơ PEDIGREE và ngựa đã rút.
 */
public record HorseIdentityRequest(
        @NotBlank(message = "Registration code is required")
        @Size(max = 30, message = "Registration code must be at most 30 characters")
        String registrationCode,

        @Size(max = 20, message = "Stall must be at most 20 characters")
        String stallNo
) {}
