package racehorse.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Bác sĩ trả lời một lần gọi: quyết định, HLV phải làm gì, dùng thuốc gì ngay. */
public record VetCallAnswer(
        @NotBlank(message = "Choose a decision")
        @Pattern(regexp = "CONTINUE_LIGHT|TREAT_ON_SITE|EMERGENCY_STOP",
                message = "Decision must be CONTINUE_LIGHT, TREAT_ON_SITE or EMERGENCY_STOP")
        String decision,

        @NotBlank(message = "Tell the head trainer what to do")
        @Size(max = 1000, message = "Instructions must be at most 1000 characters")
        String instructions,

        @Size(max = 500, message = "Medication must be at most 500 characters")
        String medication
) {}
