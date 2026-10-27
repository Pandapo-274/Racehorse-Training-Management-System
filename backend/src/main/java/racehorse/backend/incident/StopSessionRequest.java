package racehorse.backend.incident;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Lý do dừng buổi tập. Không có lý do thì không cho dừng: bác sĩ cần biết ngựa bị gì. */
public record StopSessionRequest(
        @NotBlank(message = "Symptoms are required")
        @Size(max = 255, message = "Symptoms must be at most 255 characters")
        String symptoms,

        @Size(max = 2000, message = "Description must be at most 2000 characters")
        String description,

        @NotBlank(message = "Urgency is required")
        @Pattern(regexp = "HIGH|MEDIUM|LOW", message = "Urgency must be HIGH, MEDIUM or LOW")
        String urgency
) {}
