package racehorse.backend.dto;

// Trạng thái mới của buổi tập (STOPPED_EARLY) và báo cáo sự cố vừa được tạo.
public record StopSessionResponse(int sessionId, String sessionStatus, IncidentReportResponse report) {}
