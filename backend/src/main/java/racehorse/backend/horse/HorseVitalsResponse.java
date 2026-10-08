package racehorse.backend.horse;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** UC8: ảnh chụp tình trạng hiện tại của ngựa, ghép từ nhiều bảng. */
public record HorseVitalsResponse(
        int horseId, String horseName, String status, String stallNo,
        ActiveLock activeLock,          // null nếu không bị khóa
        LatestSession latestSession,    // null nếu chưa có buổi tập
        List<OpenAlert> openAlerts,     // cảnh báo chưa có người xử lý
        List<OpenInjury> openInjuries   // chấn thương chưa hồi phục
) {
    public record ActiveLock(String lockLevel, String reason, LocalDateTime issuedAt) {}

    public record LatestSession(LocalDateTime sessionDate, String status, Integer avgHeartRate,
                                Integer peakHeartRate, Integer recoveryHeartRate,
                                BigDecimal peakVelocity, String evaluation,
                                Integer heartRateCeiling) {}

    public record OpenAlert(int alertId, String metricType, BigDecimal thresholdValue,
                            BigDecimal actualValue, String severity, LocalDateTime triggeredAt) {}

    public record OpenInjury(String bodyLocation, String severity, String status,
                             LocalDateTime markedAt) {}
}