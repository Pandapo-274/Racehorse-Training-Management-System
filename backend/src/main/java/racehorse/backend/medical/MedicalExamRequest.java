package racehorse.backend.medical;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * UC14 - những gì bác sĩ thú y khai khi ghi một lần khám.
 *
 * KHÔNG có vetId: lấy từ token, giống ProfileController. Nhận vetId từ client
 * là cho phép ghi một lần khám dưới tên bác sĩ khác.
 *
 * KHÔNG có healthStatus = 'ELIGIBLE': xem ghi chú trong MedicalRecordService.
 */
public record MedicalExamRequest(

        @NotNull(message = "Horse is required")
        Integer horseId,

        /** Báo cáo sự cố mà lần khám này trả lời. Null khi khám định kỳ. */
        Integer reportId,

        @Pattern(regexp = "EXAM|VACCINATION|DEWORMING|FARRIER",
                 message = "Record type must be EXAM, VACCINATION, DEWORMING or FARRIER")
        String recordType,

        @NotNull(message = "Examination time is required")
        @PastOrPresent(message = "Examination time cannot be in the future")
        LocalDateTime examDate,

        @Size(max = 255, message = "Diagnosis must be at most 255 characters")
        String diagnosis,

        /** MONITOR, INJURED hoặc QUARANTINE. Để trống nếu lần khám không đổi
            tình trạng con ngựa. */
        @Pattern(regexp = "MONITOR|INJURED|QUARANTINE",
                 message = "Health status must be MONITOR, INJURED or QUARANTINE. "
                         + "Clearing a horse back to ELIGIBLE is done when confirming recovery")
        String healthStatus,

        boolean injuryConfirmed,

        @Min(value = 0, message = "Rest days cannot be negative")
        Integer expectedRestDays,

        /** Hạn tái khám hoặc hạn mũi tiêm kế tiếp. Cột này là thứ màn nhắc lịch
            chăm sóc định kỳ đọc, nên nó phải ở tương lai mới có nghĩa. */
        @Future(message = "The next due date must be in the future")
        LocalDate nextDueDate,

        String notes,

        /** Cân nặng đo lúc khám (kg). Để trống nếu lần khám không cân. Có giá
            trị thì ghi đè HORSE.weight: đây là đường duy nhất cập nhật cân
            nặng, vì chủ ngựa không được tự khai (xem HorseUpdateRequest). */
        @DecimalMin(value = "30", message = "Weight must be between 30 and 1500 kg")
        @DecimalMax(value = "1500", message = "Weight must be between 30 and 1500 kg")
        BigDecimal weight
) {}
