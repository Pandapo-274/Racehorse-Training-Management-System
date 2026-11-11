package racehorse.backend.incident;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Yêu cầu bàn giao ca khám/sự cố (V40):
 * - targetVetId: id bác sĩ tiếp nhận mới. Nếu null: trả ca về hàng đợi chung (PENDING).
 * - handoverNotes: ghi chú bàn giao bắt buộc (tình trạng ngựa, thuốc đã dùng, lưu ý theo dõi).
 */
public record HandoverIncidentRequest(
        Integer targetVetId,

        @NotBlank(message = "Handover notes are required")
        @Size(max = 1000, message = "Handover notes must be at most 1000 characters")
        String handoverNotes
) {}
