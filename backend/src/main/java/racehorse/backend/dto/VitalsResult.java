package racehorse.backend.dto;

// Trả lời cho mỗi lần gửi số đo: alertRaised = true nếu vừa sinh cảnh báo (kèm alertId,
// severity, loại chỉ số và câu thông báo để màn Groom hiện ngay).
public record VitalsResult(Integer heartRate, Integer heartRateCeiling, boolean alertRaised,
                           String severity, Integer alertId, String metricType, String message,
                           boolean sessionStoppedEarly) {

    // Constructor phụ cho code biết alert nhưng chưa biết sessionStoppedEarly.
    public VitalsResult(Integer heartRate, Integer heartRateCeiling, boolean alertRaised,
                        String severity, Integer alertId, String metricType, String message) {
        this(heartRate, heartRateCeiling, alertRaised, severity, alertId, metricType, message, false);
    }

    // Constructor phụ cho code cũ chỉ biết cảnh báo nhịp tim.
    public VitalsResult(Integer heartRate, Integer heartRateCeiling, boolean alertRaised,
                        String severity, Integer alertId) {
        this(heartRate, heartRateCeiling, alertRaised, severity, alertId, null, null, false);
    }
}
