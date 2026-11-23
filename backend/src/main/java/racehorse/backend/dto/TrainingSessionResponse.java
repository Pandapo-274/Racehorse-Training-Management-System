package racehorse.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// Một buổi tập như màn Schedule (HLV), lịch của Groom và trang Training của chủ ngựa nhìn
// thấy: kết quả GET /api/training-sessions và các thao tác start/finish/schedule/cancel.
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
        BigDecimal velocityCeiling, // V47: vận tốc cho phép tối đa của buổi tập (km/h)
        String slotType,            // V50: TRAINING | REST
        boolean conflictAlert,      // V50: true nếu cả 2 groom đều bận & hết quỹ ngày nghỉ
        Integer recoveryDurationMinutes, // V50: thời gian hồi phục từ phase (phút)
        LocalDateTime recoveryStartedAt, // V50: thời điểm chuyển sang RECOVERY
        LocalDateTime recoveryEndsAt,    // V50: thời điểm kết thúc đếm ngược recovery
        Integer actualHrr,               // V50: nhịp tim hồi phục thực tế Groom nhập (bpm)
        boolean overloadWarning,         // V50: true nếu actualHrr > plan.targetHrr
        Integer targetHrr                // V50: ngưỡng nhịp tim hồi phục mục tiêu từ plan (bpm)
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
            String earlyStopReason, String reviewStatus, BigDecimal velocityCeiling) {
        this(sessionId, phaseId, phaseNo, phaseName, horseId, horseName,
                assignedTo, assignedToName, sessionDate, plannedDistance, actualDistance,
                durationMinutes, avgHeartRate, peakHeartRate, recoveryHeartRate,
                peakVelocity, evaluation, remarks, status, heartRateCeiling,
                alertCount, trialRun, videoUrl, plannedDuration, startedAt, endedAt,
                earlyStopReason, reviewStatus, velocityCeiling,
                "TRAINING", false, 15, null, null, null, false, null);
    }

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
                earlyStopReason, reviewStatus, null,
                "TRAINING", false, 15, null, null, null, false, null);
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
                alertCount, trialRun, videoUrl, 60, null, null, null, "NONE", null,
                "TRAINING", false, 15, null, null, null, false, null);
    }
}
