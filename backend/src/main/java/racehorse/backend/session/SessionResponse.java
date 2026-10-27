package racehorse.backend.session;

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
        boolean trialRun,       // lượt chạy thử
        String videoUrl         // link video chạy thử, null nếu chưa có
) {}
