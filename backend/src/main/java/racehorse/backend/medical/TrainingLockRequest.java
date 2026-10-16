package racehorse.backend.medical;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * UC16 - lệnh khoá tập khẩn cấp.
 *
 * injuryId để NULL được, và đó chính là điểm của chữ "khẩn cấp": người ở
 * trường thấy con ngựa đi cà nhắc thì khoá ngay, bác sĩ khám sau. Bắt buộc
 * phải có chẩn đoán trước khi khoá được là bỏ mất đúng cái tình huống mà lệnh
 * khoá tồn tại để xử lý - khoảng thời gian giữa lúc phát hiện và lúc có người
 * khám.
 *
 * KHÔNG có issuedBy: lấy từ token. Một lệnh cấm ngựa tập phải ghi đúng tên
 * người ra lệnh, không để client tự khai.
 */
public record TrainingLockRequest(

        @NotNull(message = "Horse is required")
        Integer horseId,

        /** Chấn thương là căn cứ của lệnh khoá. Null khi khoá khẩn cấp hoặc
            khoá vì lý do hành chính. */
        Integer injuryId,

        @NotBlank(message = "A reason is required")
        @Size(max = 255, message = "The reason must be at most 255 characters")
        String reason,

        @NotNull(message = "Lock level is required")
        @Pattern(regexp = "FULL|HEAVY_WORK_ONLY",
                 message = "Lock level must be FULL or HEAVY_WORK_ONLY")
        String lockLevel
) {}
