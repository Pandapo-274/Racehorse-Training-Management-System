package racehorse.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * trialRun = true  : đánh dấu buổi này là lượt chạy thử, có thể kèm link video.
 * trialRun = false : bỏ đánh dấu (link video, nếu có, bị xoá theo vì chỉ lượt chạy thử mới có video).
 * videoUrl bỏ trống / null = không có video (hoặc xoá video đang có).
 */
// Body của PUT /api/sessions/{id}/trial-run (chỉ HEAD_TRAINER).
public record TrialRunRequest(
        @NotNull(message = "trialRun is required")
        Boolean trialRun,

        @Size(max = 500, message = "Video link must be at most 500 characters")
        String videoUrl
) {}
