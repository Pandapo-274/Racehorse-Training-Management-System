package racehorse.backend.alert;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ResolveAlertRequest(
        @NotBlank(message = "Action is required")
        @Pattern(regexp = "CONTINUE|REDUCE_INTENSITY|STOP_SESSION|VET_CALLED",
                message = "Action must be CONTINUE, REDUCE_INTENSITY, STOP_SESSION or VET_CALLED")
        String actionTaken
) {}
