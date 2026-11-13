package racehorse.backend.dto;

public record StopSessionResponse(int sessionId, String sessionStatus, IncidentReportResponse report) {}
