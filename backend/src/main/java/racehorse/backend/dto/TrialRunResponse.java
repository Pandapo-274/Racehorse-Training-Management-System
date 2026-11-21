package racehorse.backend.dto;

// Trạng thái chạy thử của buổi tập sau khi cập nhật.
public record TrialRunResponse(int sessionId, boolean trialRun, String videoUrl) {}
