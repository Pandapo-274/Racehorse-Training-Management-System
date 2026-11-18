package racehorse.backend.dto;

import java.math.BigDecimal;
import java.time.Instant;

/*
 * DTO (Data Transfer Object) = record chỉ dùng để chở dữ liệu qua API, không có logic.
 * Request DTO: JSON frontend gửi lên, Jackson đọc vào record (theo tên khoá JSON = tên
 * thành phần). Response DTO: record service trả ra, Jackson ghi thành JSON.
 *
 * AlertResponse: một cảnh báo vượt ngưỡng (bảng THRESHOLD_ALERT) đã ghép tên ngựa và tên
 * người xử lý. Trả về bởi GET /api/alerts (AlertController). resolved = actionTaken != null.
 * metricType: HEART_RATE | VELOCITY | WORKLOAD; severity: HIGH | MEDIUM | LOW.
 */
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
