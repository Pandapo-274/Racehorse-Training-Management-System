package racehorse.backend.medical;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * UC15 - một dấu chấn thương bác sĩ ghim lên mô hình giải phẫu.
 *
 * recordId là hồ sơ khám (UC14) mà dấu này thuộc về, KHÔNG phải horseId.
 * INJURY trong lược đồ không có horse_id - con ngựa suy ra qua
 * MEDICAL_RECORD. Nhận horseId ở đây rồi tự đoán hồ sơ là đoán sai: một con
 * ngựa có nhiều lần khám, và ghim dấu vào lần khám nào quyết định dấu đó
 * thuộc chẩn đoán nào.
 *
 * Toạ độ tính theo phần trăm chiều rộng/chiều cao của mô hình (0-100), không
 * phải pixel: mô hình hiển thị ở nhiều kích thước khác nhau trên các màn hình
 * khác nhau, nên lưu pixel là dấu sẽ lệch chỗ khi đổi khung.
 */
public record InjuryMarkerRequest(

        @NotNull(message = "Medical record is required")
        Integer recordId,

        @NotBlank(message = "Body location is required")
        @Size(max = 100, message = "Body location must be at most 100 characters")
        String bodyLocation,

        @DecimalMin(value = "0", message = "Coordinate X must be between 0 and 100")
        @DecimalMax(value = "100", message = "Coordinate X must be between 0 and 100")
        BigDecimal coordinateX,

        @DecimalMin(value = "0", message = "Coordinate Y must be between 0 and 100")
        @DecimalMax(value = "100", message = "Coordinate Y must be between 0 and 100")
        BigDecimal coordinateY,

        @NotNull(message = "Severity is required")
        @Pattern(regexp = "GRADE_1|GRADE_2|GRADE_3",
                 message = "Severity must be GRADE_1, GRADE_2 or GRADE_3")
        String severity
) {}
