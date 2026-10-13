package racehorse.backend.medical;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Một lần khám, như frontend nhìn thấy.
 *
 * horseName và vetName ghép sẵn từ HORSE và APP_USER để màn hình không phải
 * gọi thêm một vòng nữa chỉ để đổi id thành tên - cùng cách HorseResponse làm
 * với ownerName.
 */
public record MedicalRecordRow(
        int recordId,
        int horseId, String horseName,
        int vetId, String vetName,
        Integer reportId,
        String recordType,
        LocalDateTime examDate,
        String diagnosis,
        String healthStatus,
        boolean injuryConfirmed,
        Integer expectedRestDays,
        LocalDate nextDueDate,
        String notes
) {}
