package racehorse.backend.auth;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** Lỗi nghiệp vụ có chủ đích: service ném, GlobalExceptionHandler bắt và đổi thành JSON. */
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
