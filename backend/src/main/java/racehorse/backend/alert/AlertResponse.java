package racehorse.backend.alert;

import java.math.BigDecimal;
import java.time.Instant;

public record AlertResponse(
        int alertId,
        int sessionId,
        int horseId,
        String horseName,
        String metricType,
        BigDecimal thresholdValue,
        BigDecimal actualValue,
        String severity,
        Instant triggeredAt,
        String actionTaken,
        Integer resolvedBy,
        String resolvedByName,
        boolean resolved
) {}
