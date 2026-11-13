package racehorse.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Head Trainer huỷ báo động giả: bắt buộc nói vì sao, để bác sĩ và chủ ngựa đọc được. */
public record DismissIncidentRequest(
        @NotBlank(message = "Say why this is a false alarm")
        @Size(max = 500, message = "Reason must be at most 500 characters")
        String reason
) {}
