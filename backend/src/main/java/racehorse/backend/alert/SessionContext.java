package racehorse.backend.alert;

import java.math.BigDecimal;

/** Những gì cần biết về một ca tập để xử lý số đo: ai phụ trách, đang chạy không, ngưỡng nhịp tim & tốc độ tối đa là bao nhiêu. */
public record SessionContext(int sessionId, int assignedTo, String status, Integer heartRateCeiling,
                             int horseId, BigDecimal velocityCeiling) {
    public SessionContext(int sessionId, int assignedTo, String status, Integer heartRateCeiling) {
        this(sessionId, assignedTo, status, heartRateCeiling, 0, null);
    }
}
