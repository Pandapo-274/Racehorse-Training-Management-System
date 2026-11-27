package racehorse.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * V63: a person who may decide for an owner's horses when the owner cannot be reached.
 * ownerId: only the Club Manager sends it (adding the buyer's contact during a transfer).
 */
public record OwnerContactRequest(
        Integer ownerId,

        @NotBlank(message = "Give the person's full name")
        @Size(max = 100, message = "Name must be at most 100 characters")
        String fullName,

        @NotBlank(message = "Give their phone number")
        @Pattern(regexp = "^[0-9+()\\s.-]{8,20}$", message = "Enter a valid phone number")
        String phone,

        @Size(max = 50, message = "At most 50 characters")
        String relationship
) {}
