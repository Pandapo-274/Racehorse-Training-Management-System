package racehorse.backend.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** Một lần gọi bác sĩ, kèm cảnh báo gốc và câu trả lời (nếu đã có). Giờ là UTC. */
// Kết quả GET /api/vet-calls, GET /api/vet-calls/{callId} và các thao tác gọi/trả lời.
// alertId null khi cuộc gọi do Groom tạo lúc dừng buổi vì chấn thương (V38).
public record VetCallResponse(
        int callId,
        Integer alertId,
        int sessionId,
        String sessionStatus,
        int horseId,
        String horseName,
        String stallNo,
        String horseStatus,
        String groomName,
        String metricType,
        BigDecimal thresholdValue,
        BigDecimal actualValue,
        String severity,
        Instant triggeredAt,
        int calledBy,
        String calledByName,
        String reason,
        String observations,
        String urgency,
        boolean sessionStopped,
        Instant calledAt,
        String status,
        Integer answeredBy,
        String answeredByName,
        Instant answeredAt,
        String decision,
        String instructions,
        String medication,
        Integer reportId
) {}
