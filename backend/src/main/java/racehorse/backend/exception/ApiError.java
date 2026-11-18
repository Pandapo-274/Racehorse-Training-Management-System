package racehorse.backend.exception;

import java.time.Instant;
import java.util.Map;

/** Khuôn lỗi thống nhất cho toàn bộ API: frontend chỉ cần xử lý 1 dạng. */
/*
 * Ví dụ JSON trả về khi lỗi:
 *   { "status": 400, "message": "Validation failed",
 *     "errors": { "name": "Name is required" }, "timestamp": "2026-11-17T03:00:00Z" }
 * Frontend (authFetch/các *Api.js) đọc "message" để hiện thông báo và "errors" để
 * tô đỏ từng ô nhập. Đổi khuôn này là phải sửa cả frontend.
 */
public record ApiError(int status, String message, Map<String, String> errors, Instant timestamp) {
    public static ApiError of(int status, String message, Map<String, String> errors) {
        return new ApiError(status, message, errors, Instant.now());
    }
}
