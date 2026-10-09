package racehorse.backend.horserequest;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Một yêu cầu đăng ký ngựa, như frontend nhìn thấy.
 *
 * ownerName và reviewerName ghép sẵn từ APP_USER để màn hình không phải gọi
 * thêm một vòng nữa chỉ để đổi id thành tên.
 */
public record HorseRequestRow(
        int requestId,
        int ownerId, String ownerName,
        String horseName, String breed, String horseGender,
        LocalDate dateOfBirth, String color, String note,
        String status, LocalDateTime createdAt,
        Integer reviewedBy, String reviewerName, LocalDateTime reviewedAt, String reviewNote,
        /** Chỉ có khi đã duyệt: id con ngựa được tạo ra từ yêu cầu này. */
        Integer horseId
) {}
