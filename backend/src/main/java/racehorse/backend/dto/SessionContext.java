package racehorse.backend.dto;

import java.math.BigDecimal;

/** Những gì cần biết về một ca tập để xử lý số đo: ai phụ trách, đang chạy không, ngưỡng nhịp tim & tốc độ tối đa là bao nhiêu. */
// Dữ liệu nội bộ (không trả ra API): AlertRepository đọc khi nhận số đo, để AlertService biết
// Groom gửi có đúng là người được giao không, buổi có đang RUNNING không và ngưỡng là bao nhiêu.
public record SessionContext(int sessionId, int assignedTo, String status, Integer heartRateCeiling,
                             int horseId, BigDecimal velocityCeiling) {
    public SessionContext(int sessionId, int assignedTo, String status, Integer heartRateCeiling) {
        this(sessionId, assignedTo, status, heartRateCeiling, 0, null);
    }
}
