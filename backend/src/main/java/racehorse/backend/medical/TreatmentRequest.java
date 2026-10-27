package racehorse.backend.medical;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * UC14 - một mục điều trị kê theo một lần khám.
 *
 * Mọi trường trừ recordId và startDate đều có thể để trống, đúng như lược đồ
 * V1 cho phép: không phải lần kê nào cũng là thuốc. "Chườm lạnh 20 phút, 3
 * lần/ngày" là một mục điều trị hợp lệ mà medicine_name không phải tên thuốc,
 * và dữ liệu demo của V3 có đúng một dòng như vậy. Bắt khai đủ tên thuốc và
 * liều sẽ chặn chính những mục đó.
 *
 * endDate để trống nghĩa là điều trị chưa định ngày kết thúc - thường gặp với
 * thuốc dùng đến khi tái khám.
 */
public record TreatmentRequest(

        @NotNull(message = "Medical record is required")
        Integer recordId,

        @Size(max = 150, message = "Medicine name must be at most 150 characters")
        String medicineName,

        @Size(max = 50, message = "Dosage must be at most 50 characters")
        String dosage,

        @Size(max = 50, message = "Frequency must be at most 50 characters")
        String frequency,

        String instruction,

        @NotNull(message = "Start date is required")
        LocalDate startDate,

        LocalDate endDate
) {}
