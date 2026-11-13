package racehorse.backend.training;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TrainingSessionResponse(
        int sessionId, int phaseId, int phaseNo, String phaseName,
        int horseId, String horseName, int assignedTo, String assignedToName,
        LocalDateTime sessionDate, Integer plannedDistance, Integer actualDistance,
        Integer durationMinutes, Integer avgHeartRate, Integer peakHeartRate,
        Integer recoveryHeartRate, BigDecimal peakVelocity, String evaluation,
        String remarks, String status,
        Integer heartRateCeiling,   // ngưỡng nhịp tim của giai đoạn
        int alertCount,             // số cảnh báo đã sinh từ buổi tập này
        boolean trialRun,           // lượt chạy thử
        String videoUrl,            // link video chạy thử, null nếu chưa có
        Integer plannedDuration,    // V37: thời lượng đặt lịch (phút, tối đa 720)
        LocalDateTime startedAt,    // V37: thời điểm bắt đầu thực tế
        LocalDateTime endedAt,      // V37: thời điểm kết thúc thực tế
        String earlyStopReason,     // V37: lý do kết thúc sớm do Groom chọn
        String reviewStatus,        // V37: NONE | PENDING | ARCHIVED_AUTO | REVIEWED
        BigDecimal velocityCeiling  // V45: vận tốc cho phép tối đa của buổi tập (m/s)
) {
    public TrainingSessionResponse(
            int sessionId, int phaseId, int phaseNo, String phaseName,
            int horseId, String horseName, int assignedTo, String assignedToName,
            LocalDateTime sessionDate, Integer plannedDistance, Integer actualDistance,
            Integer durationMinutes, Integer avgHeartRate, Integer peakHeartRate,
            Integer recoveryHeartRate, BigDecimal peakVelocity, String evaluation,
            String remarks, String status,
            Integer heartRateCeiling, int alertCount, boolean trialRun, String videoUrl,
            Integer plannedDuration, LocalDateTime startedAt, LocalDateTime endedAt,
            String earlyStopReason, String reviewStatus) {
        this(sessionId, phaseId, phaseNo, phaseName, horseId, horseName,
                assignedTo, assignedToName, sessionDate, plannedDistance, actualDistance,
                durationMinutes, avgHeartRate, peakHeartRate, recoveryHeartRate,
                peakVelocity, evaluation, remarks, status, heartRateCeiling,
                alertCount, trialRun, videoUrl, plannedDuration, startedAt, endedAt,
                earlyStopReason, reviewStatus, null);
    }

    public TrainingSessionResponse(
            int sessionId, int phaseId, int phaseNo, String phaseName,
            int horseId, String horseName, int assignedTo, String assignedToName,
            LocalDateTime sessionDate, Integer plannedDistance, Integer actualDistance,
            Integer durationMinutes, Integer avgHeartRate, Integer peakHeartRate,
            Integer recoveryHeartRate, BigDecimal peakVelocity, String evaluation,
            String remarks, String status,
            Integer heartRateCeiling, int alertCount, boolean trialRun, String videoUrl) {
        this(sessionId, phaseId, phaseNo, phaseName, horseId, horseName,
                assignedTo, assignedToName, sessionDate, plannedDistance, actualDistance,
                durationMinutes, avgHeartRate, peakHeartRate, recoveryHeartRate,
                peakVelocity, evaluation, remarks, status, heartRateCeiling,
                alertCount, trialRun, videoUrl, 60, null, null, null, "NONE", null);
    }
}
