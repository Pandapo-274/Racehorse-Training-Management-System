package racehorse.backend.medical;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Một dấu chấn thương, như mô hình giải phẫu nhìn thấy.
 *
 * Kèm vetName và examDate ghép từ MEDICAL_RECORD và APP_USER: khi bác sĩ trỏ
 * vào một dấu trên mô hình, câu hỏi đầu tiên là "ai ghim, khám hôm nào", và
 * nếu không ghép sẵn thì màn hình phải gọi thêm một vòng cho từng dấu.
 *
 * recoveredAt null nghĩa là dấu còn hiệu lực. Giữ cả dấu đã RECOVERED trong
 * cùng một kiểu để mô hình vẽ được bệnh sử - chỗ nào hay chấn thương lặp lại
 * chỉ thấy được khi vẽ cả dấu cũ.
 */
public record InjuryMarkerRow(
        int injuryId,
        int recordId,
        int horseId,
        String horseName,
        String bodyLocation,
        BigDecimal coordinateX,
        BigDecimal coordinateY,
        String severity,
        String status,
        LocalDateTime markedAt,
        LocalDateTime recoveredAt,
        String vetName,
        LocalDateTime examDate
) {}
