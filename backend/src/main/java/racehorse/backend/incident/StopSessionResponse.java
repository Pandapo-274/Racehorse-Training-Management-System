package racehorse.backend.incident;

public record StopSessionResponse(int sessionId, String sessionStatus, IncidentReportResponse report) {}
