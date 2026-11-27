package racehorse.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// Một buổi tập như màn đánh giá (UC12) và trang Training của chủ ngựa nhìn thấy: kết quả
// GET /api/sessions, GET /api/sessions/{id}, PUT /api/sessions/{id}/evaluation.
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
        LocalDateTime autoReviewedAt, // V37: thời điểm hệ thống tự động tạo báo cáo sơ bộ
        BigDecimal targetVelocity,    // V54: target speed từ PLAN_PHASE (km/h)
        Integer targetHrr,            // V54: target HRR từ TRAINING_PLAN (bpm)
        Integer actualHrr             // V54: nhịp tim hồi phục thực tế Groom nhập (bpm)
) {
    public SessionResponse(
            int sessionId, int phaseId, String phaseName, Integer heartRateCeiling,
            int horseId, String horseName, int assignedTo, String assignedToName,
            LocalDateTime sessionDate, Integer plannedDistance, Integer actualDistance,
            Integer durationMinutes, Integer avgHeartRate, Integer peakHeartRate,
            Integer recoveryHeartRate, BigDecimal peakVelocity, String evaluation,
            String remarks, Integer evaluatedBy, String status, boolean trialRun, String videoUrl,
            Integer plannedDuration, LocalDateTime endedAt, String earlyStopReason,
            String reviewStatus, LocalDateTime autoReviewedAt) {
        this(sessionId, phaseId, phaseName, heartRateCeiling, horseId, horseName,
                assignedTo, assignedToName, sessionDate, plannedDistance, actualDistance,
                durationMinutes, avgHeartRate, peakHeartRate, recoveryHeartRate,
                peakVelocity, evaluation, remarks, evaluatedBy, status, trialRun, videoUrl,
                plannedDuration, endedAt, earlyStopReason, reviewStatus, autoReviewedAt,
                null, null, null);
    }

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
                60, null, null, "NONE", null, null, null, null);
    }
}
