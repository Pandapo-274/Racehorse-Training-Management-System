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
        String videoUrl             // link video chạy thử, null nếu chưa có
) {}