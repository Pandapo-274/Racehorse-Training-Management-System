package racehorse.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// Body của PUT /api/external-referrals/{id}/discharge: bác sĩ ghi ngựa xuất viện ngoài trở
// về. postDischargeStatus = tình trạng sức khoẻ đặt cho ngựa sau khi về (INJURED/MONITOR). Không nhận ELIGIBLE:
// theo đặc tả, chỉ xác nhận hồi phục mới đưa ngựa về ELIGIBLE.
public record DischargeReferralRequest(
        @NotBlank(message = "Discharge notes are required")
        @Size(max = 1000, message = "Discharge notes must be at most 1000 characters")
        String dischargeNotes,

        @Pattern(regexp = "INJURED|MONITOR", message = "Post discharge status must be INJURED or MONITOR")
        String postDischargeStatus
) {}
