package racehorse.backend.incident;

import java.time.Instant;

/** Một báo cáo sự cố, đủ để vẽ một dòng trong hàng chờ khám của bác sĩ. */
public record IncidentReportResponse(
        int reportId,
        int horseId,
        String horseName,
        String stallNo,
        String horseStatus,
        int ownerId,
        Integer sessionId,          // null nếu báo cáo không sinh ra từ một buổi tập
        int reporterId,
        String reporterName,
        String symptoms,
        String description,
        String photoUrl,
        String urgency,             // HIGH | MEDIUM | LOW
        Instant reportedAt,
        String status,              // PENDING | RECEIVED | EXAMINED
        Integer receivedBy,
        String receivedByName,
        Instant receivedAt
) {}
