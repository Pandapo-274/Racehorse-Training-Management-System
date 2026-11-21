package racehorse.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Chủ ngựa ký hợp đồng online: gõ đúng họ tên của mình, tích đồng ý, và gửi kèm
 * mã SHA-256 của bản hợp đồng đang nhìn thấy. Server chỉ nhận chữ ký khi mã đó
 * trùng bản đã lưu, nên không ai ký được một bản khác với bản đang hiện.
 */
public record SignContractRequest(
        @NotBlank(message = "Type your full name to sign")
        @Size(max = 100, message = "Name must be at most 100 characters")
        String fullName,

        Boolean agree,

        @NotBlank(message = "The contract fingerprint is missing; reload the page")
        @Size(min = 64, max = 64, message = "The contract fingerprint is invalid; reload the page")
        String termsSha256
) {}
