package racehorse.backend.dto;

import java.time.Instant;

/** Một báo cáo sự cố, đủ để vẽ một dòng trong hàng chờ khám của bác sĩ. */
// Trả về bởi GET /api/incident-reports, GET /{id}, POST /api/incident-reports và kèm trong
// StopSessionResponse. Instant = giờ UTC (JSON có hậu tố "Z").
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
        String status,              // PENDING | RECEIVED | EXAMINED | DISMISSED
        Integer receivedBy,
        String receivedByName,
        Instant receivedAt,
        String dismissedByName,     // Head Trainer huỷ báo động giả (V34), null nếu chưa huỷ
        String dismissReason,
        Integer handoverById,       // V40: Bác sĩ/Quản lý bàn giao ca
        String handoverByName,
        Instant handoverAt,
        String handoverNotes
) {
    // Constructor phụ cho các câu SQL chưa đọc cột bàn giao ca (V40): điền null.
    public IncidentReportResponse(
            int reportId, int horseId, String horseName, String stallNo, String horseStatus,
            int ownerId, Integer sessionId, int reporterId, String reporterName,
            String symptoms, String description, String photoUrl, String urgency,
            Instant reportedAt, String status, Integer receivedBy, String receivedByName,
            Instant receivedAt, String dismissedByName, String dismissReason) {
        this(reportId, horseId, horseName, stallNo, horseStatus, ownerId, sessionId, reporterId,
                reporterName, symptoms, description, photoUrl, urgency, reportedAt, status,
                receivedBy, receivedByName, receivedAt, dismissedByName, dismissReason,
                null, null, null, null);
    }
}
