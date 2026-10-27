package racehorse.backend.withdrawal;

import jakarta.validation.constraints.Size;

/**
 * Ghi chú của Club Manager khi xử lý yêu cầu rút ngựa.
 * Không bắt buộc khi duyệt, bắt buộc khi từ chối (service kiểm).
 */
public record WithdrawalReviewRequest(
        @Size(max = 255, message = "Note must be at most 255 characters")
        String note
) {}
