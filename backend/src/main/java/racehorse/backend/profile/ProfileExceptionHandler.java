package racehorse.backend.profile;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import racehorse.backend.auth.ApiError;

/** Bắt lỗi "file quá lớn" do Spring ném ra TRƯỚC khi vào controller, để trả đúng khuôn ApiError. */
@RestControllerAdvice
public class ProfileExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> tooLarge(MaxUploadSizeExceededException ex) {
        String msg = "File is too large (max 2 MB)";
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiError.of(413, msg, Map.of("file", msg)));
    }
}
