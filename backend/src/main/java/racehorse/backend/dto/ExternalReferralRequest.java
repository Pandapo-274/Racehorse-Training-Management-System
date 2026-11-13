package racehorse.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record ExternalReferralRequest(
        @NotNull(message = "Horse is required")
        Integer horseId,

        Integer reportId,

        @NotBlank(message = "Hospital name is required")
        @Size(max = 255, message = "Hospital name must be at most 255 characters")
        String hospitalName,

        @NotBlank(message = "Referral reason is required")
        @Size(max = 1000, message = "Referral reason must be at most 1000 characters")
        String referralReason,

        @NotBlank(message = "Urgency is required")
        @Pattern(regexp = "HIGH|CRITICAL", message = "Urgency must be HIGH or CRITICAL")
        String urgency,

        LocalDate expectedReturn
) {}
