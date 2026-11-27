package racehorse.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** V62: the club withdraws a horse whose stay ended without renewal; the owner is told why. */
public record WithdrawalByClubRequest(
        @NotNull(message = "Choose a horse")
        Integer horseId,

        @NotBlank(message = "Say why the club is withdrawing the horse")
        @Size(max = 500, message = "Reason must be at most 500 characters")
        String reason
) {}
