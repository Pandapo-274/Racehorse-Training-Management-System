package racehorse.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Head Trainer huỷ báo động giả: bắt buộc nói vì sao, để bác sĩ và chủ ngựa đọc được. */
// Body của PUT /api/incident-reports/{id}/dismiss (IncidentController). Chỉ huỷ được khi
// báo cáo còn PENDING (bác sĩ chưa nhận ca); báo cáo chuyển sang DISMISSED (V34).
public record DismissIncidentRequest(
        @NotBlank(message = "Say why this is a false alarm")
        @Size(max = 500, message = "Reason must be at most 500 characters")
        String reason
) {}
