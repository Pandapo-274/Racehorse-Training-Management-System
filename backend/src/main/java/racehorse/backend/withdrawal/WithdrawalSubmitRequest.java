package racehorse.backend.withdrawal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Chủ ngựa xin rút một con ngựa khỏi học viện.
 *
 * Lý do là bắt buộc: Club Manager cần biết vì sao trước khi huỷ lịch tập và
 * kết thúc giáo án của con ngựa.
 */
public record WithdrawalSubmitRequest(
        @NotNull(message = "Choose a horse")
        Integer horseId,

        @NotBlank(message = "Say why the horse is leaving the club")
        @Size(max = 500, message = "Reason must be at most 500 characters")
        String reason
) {}
