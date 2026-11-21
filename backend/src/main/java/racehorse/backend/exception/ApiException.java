package racehorse.backend.exception;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** Lỗi nghiệp vụ có chủ đích: service ném, GlobalExceptionHandler bắt và đổi thành JSON. */
/*
 * Cách dùng trong service:
 *   throw new ApiException(HttpStatus.NOT_FOUND, "Horse not found");               // 404
 *   throw new ApiException(HttpStatus.CONFLICT, "Alert has already been resolved"); // 409
 *   throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid data",
 *                          Map.of("sessionDate", "Must be in the future"));        // 400 + lỗi từng ô
 * Là RuntimeException (unchecked) nên không phải khai báo "throws". Khi ném bên trong
 * method @Transactional, Spring tự ROLLBACK giao dịch (mặc định rollback với
 * RuntimeException), nên mọi câu INSERT/UPDATE trước đó trong cùng method bị huỷ.
 *
 * Quy ước mã lỗi dùng trong dự án: 400 dữ liệu sai, 401 chưa đăng nhập/token hỏng,
 * 403 sai vai trò hoặc không phải dữ liệu của mình, 404 không tìm thấy, 409 xung đột
 * trạng thái (đã duyệt rồi, trùng giờ...), 413 file quá lớn, 428 phải đổi mật khẩu tạm,
 * 429 khoá đăng nhập tạm thời, 503 tính năng chưa cấu hình.
 */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final Map<String, String> fieldErrors;

    public ApiException(HttpStatus status, String message) {
        this(status, message, Map.of());
    }

    public ApiException(HttpStatus status, String message, Map<String, String> fieldErrors) {
        super(message);
        this.status = status;
        this.fieldErrors = fieldErrors;
    }

    public HttpStatus getStatus() { return status; }
    public Map<String, String> getFieldErrors() { return fieldErrors; }
}
