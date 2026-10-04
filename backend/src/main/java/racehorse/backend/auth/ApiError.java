package racehorse.backend.auth;

import java.time.Instant;
import java.util.Map;

/** Khuôn lỗi thống nhất cho toàn bộ API: frontend chỉ cần xử lý 1 dạng. */
public record ApiError(int status, String message, Map<String, String> errors, Instant timestamp) {
    public static ApiError of(int status, String message, Map<String, String> errors) {
        return new ApiError(status, message, errors, Instant.now());
    }
}
