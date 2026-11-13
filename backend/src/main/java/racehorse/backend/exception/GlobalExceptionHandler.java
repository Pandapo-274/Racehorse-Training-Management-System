package racehorse.backend.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** @Valid fail -> 400 kèm lỗi theo từng field. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> errors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        return ResponseEntity.badRequest().body(ApiError.of(400, "Validation failed", errors));
    }

    /** JSON gửi lên bị hỏng / thiếu body. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
        String msg = ex.getMessage() != null ? ex.getMessage() : "";
        if (msg.contains("out of range of int") || msg.contains("plannedDistance")) {
            return ResponseEntity.badRequest().body(ApiError.of(400,
                    "Planned distance cannot exceed 5,000 meters",
                    Map.of("plannedDistance", "Planned distance cannot exceed 5,000 meters")));
        }
        return ResponseEntity.badRequest().body(ApiError.of(400, "Malformed JSON request body", Map.of()));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex) {
        HttpStatus s = ex.getStatus();
        return ResponseEntity.status(s).body(ApiError.of(s.value(), ex.getMessage(), ex.getFieldErrors()));
    }

    /** Vi phạm ràng buộc DB (trùng khoá, CHECK) mà service chưa chặn trước: 409, không phải 500. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException ex, HttpServletRequest req) {
        log.warn("Data integrity violation on {} {}: {}", req.getMethod(), req.getRequestURI(),
                ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(409,
                "This change conflicts with existing data (duplicate or invalid value)", Map.of()));
    }

    /** Sai kiểu tham số, vd. /api/horses/abc. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest().body(ApiError.of(400,
                "Invalid value for '" + ex.getName() + "'", Map.of(ex.getName(), "Invalid value")));
    }

    /**
     * Lưới an toàn cuối: mọi lỗi còn lại vẫn trả đúng khuôn ApiError cho frontend.
     * Lỗi chuẩn của Spring (404 không có tài nguyên, 405 sai phương thức, thiếu
     * tham số...) giữ nguyên mã của chúng; chỉ lỗi thật sự bất ngờ mới là 500 và
     * được ghi log đầy đủ. Không ghi log ở đây thì có handler này còn tệ hơn
     * không có, vì Spring sẽ ngừng tự in lỗi ra console.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest req) {
        if (ex instanceof ErrorResponse er) {
            int status = er.getStatusCode().value();
            String detail = er.getBody().getDetail();
            return ResponseEntity.status(status)
                    .body(ApiError.of(status, detail != null ? detail : ex.getMessage(), Map.of()));
        }
        log.error("Unhandled error on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(500, "Unexpected server error. It has been logged for the team", Map.of()));
    }
}
