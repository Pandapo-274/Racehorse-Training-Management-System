package racehorse.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Club Manager sửa định danh do câu lạc bộ cấp: mã đăng ký, số chuồng và
 * Head Trainer phụ trách (V37). stallNo và trainerId để trống với hồ sơ PEDIGREE
 * và ngựa đã rút.
 */
// Body của PUT /api/horses/{id}/identity (chỉ CLUB_MANAGER, xem HorseController).
public record HorseIdentityRequest(
        @NotBlank(message = "Registration code is required")
        @Size(max = 30, message = "Registration code must be at most 30 characters")
        String registrationCode,

        @Size(max = 20, message = "Stall must be at most 20 characters")
        String stallNo,

        Integer trainerId
) {
    // Constructor phụ cho code cũ không gửi trainerId.
    public HorseIdentityRequest(String registrationCode, String stallNo) {
        this(registrationCode, stallNo, null);
    }
}
