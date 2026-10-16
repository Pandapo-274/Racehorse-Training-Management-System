package racehorse.backend.alert;

/** Những gì cần biết về một ca tập để xử lý số đo: ai phụ trách, đang chạy không, ngưỡng nhịp tim là bao nhiêu. */
public record SessionContext(int sessionId, int assignedTo, String status, Integer heartRateCeiling) {}
