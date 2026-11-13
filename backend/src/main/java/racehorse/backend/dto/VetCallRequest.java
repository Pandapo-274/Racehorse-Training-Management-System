package racehorse.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** HLV gọi bác sĩ từ một cảnh báo: thấy gì, gấp tới đâu, có dừng buổi tập ngay không. */
public record VetCallRequest(
        @NotBlank(message = "Tell the vet why you are calling")
        @Size(max = 500, message = "Reason must be at most 500 characters")
        String reason,

        @Size(max = 1000, message = "Observations must be at most 1000 characters")
        String observations,

        @NotBlank(message = "Urgency is required")
        @Pattern(regexp = "HIGH|MEDIUM|LOW", message = "Urgency must be HIGH, MEDIUM or LOW")
        String urgency,

        /* Boolean (không phải boolean) để bỏ trống được: null nghĩa là không dừng. */
        Boolean stopSession
) {}
