package racehorse.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record SessionResponse(
        int sessionId,
        int phaseId,
        String phaseName,
        Integer heartRateCeiling,
        int horseId,
        String horseName,
        int assignedTo,
        String assignedToName,
        LocalDateTime sessionDate,
        Integer plannedDistance,
        Integer actualDistance,
        Integer durationMinutes,
        Integer avgHeartRate,
        Integer peakHeartRate,
        Integer recoveryHeartRate,
        BigDecimal peakVelocity,
        String evaluation,
        String remarks,
        Integer evaluatedBy,
        String status,
        boolean trialRun,           // lượt chạy thử
        String videoUrl,            // link video chạy thử, null nếu chưa có
        Integer plannedDuration,    // V37: thời lượng đặt lịch (phút)
        LocalDateTime endedAt,      // V37: thời điểm kết thúc thực tế (mốc tính hạn 48h review)
        String earlyStopReason,     // V37: lý do kết thúc sớm
        String reviewStatus,        // V37: NONE | PENDING | ARCHIVED_AUTO | REVIEWED
        LocalDateTime autoReviewedAt // V37: thời điểm hệ thống tự động tạo báo cáo sơ bộ
) {
    public SessionResponse(
            int sessionId, int phaseId, String phaseName, Integer heartRateCeiling,
            int horseId, String horseName, int assignedTo, String assignedToName,
            LocalDateTime sessionDate, Integer plannedDistance, Integer actualDistance,
            Integer durationMinutes, Integer avgHeartRate, Integer peakHeartRate,
            Integer recoveryHeartRate, BigDecimal peakVelocity, String evaluation,
            String remarks, Integer evaluatedBy, String status, boolean trialRun, String videoUrl) {
        this(sessionId, phaseId, phaseName, heartRateCeiling, horseId, horseName,
                assignedTo, assignedToName, sessionDate, plannedDistance, actualDistance,
                durationMinutes, avgHeartRate, peakHeartRate, recoveryHeartRate,
                peakVelocity, evaluation, remarks, evaluatedBy, status, trialRun, videoUrl,
                60, null, null, "NONE", null);
    }
}
