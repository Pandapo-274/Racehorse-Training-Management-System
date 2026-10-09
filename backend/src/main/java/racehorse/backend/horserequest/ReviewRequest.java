package racehorse.backend.horserequest;

import jakarta.validation.constraints.Size;

/**
 * Phần học viện điền khi xử lý một yêu cầu.
 *
 * Lúc DUYỆT, registrationCode là bắt buộc - nó là thứ duy nhất học viện phải
 * cấp mà chủ ngựa không có. Không kiểm bằng @NotBlank ở đây vì cùng một record
 * này cũng dùng cho lệnh TỪ CHỐI, nơi mã đăng ký không có nghĩa gì;
 * HorseRequestService kiểm nó ở đúng nhánh duyệt.
 */
public record ReviewRequest(
        @Size(max = 30, message = "Registration code must be at most 30 characters")
        String registrationCode,

        @Size(max = 20, message = "Stall must be at most 20 characters")
        String stallNo,

        @Size(max = 255, message = "Note must be at most 255 characters")
        String note
) {}
