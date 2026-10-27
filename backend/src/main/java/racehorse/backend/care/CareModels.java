package racehorse.backend.care;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Flow 4 - chăm sóc chuồng trại và dinh dưỡng hằng ngày.
 * Hai bảng có từ V1: FEEDING_SCHEDULE (khẩu phần đã duyệt) và DAILY_TASK (việc trong ngày).
 */
public final class CareModels {

    private CareModels() {
    }

    /** Một dòng khẩu phần: một loại thức ăn trong một bữa. */
    public record FeedingItem(
            int feedingId, int horseId, String horseName, String stallNo,
            String mealTime, String feedType, BigDecimal quantity, String unit,
            Integer approvedBy, String approvedByName, String note) {
    }

    /** Thay toàn bộ khẩu phần của một con ngựa (Head Trainer hoặc Vet duyệt). */
    public record RationRequest(
            @NotEmpty(message = "A ration needs at least one item")
            @Size(max = 15, message = "At most 15 items")
            @Valid List<@NotNull RationItem> items) {
    }

    public record RationItem(
            @NotBlank(message = "Meal is required")
            @Pattern(regexp = "MORNING|NOON|EVENING", message = "Meal must be MORNING, NOON or EVENING")
            String mealTime,
            @NotBlank(message = "Feed is required")
            @Size(max = 50, message = "Feed name must be at most 50 characters")
            String feedType,
            @NotNull(message = "Quantity is required")
            @DecimalMin(value = "0.01", message = "Quantity must be greater than 0")
            BigDecimal quantity,
            @NotBlank(message = "Unit is required")
            @Size(max = 20, message = "Unit must be at most 20 characters")
            String unit,
            @Size(max = 255, message = "Note must be at most 255 characters")
            String note) {
    }

    /** Một việc chăm sóc trong ngày. */
    public record Task(
            int taskId, int horseId, String horseName, String stallNo,
            String taskType, int assignedTo, String assignedToName,
            LocalDate taskDate, boolean completed, Instant completedAt, String note) {
    }

    /** Giao nhiều loại việc cho một Groom, cho một con ngựa, trong một ngày. */
    public record AssignRequest(
            @NotNull(message = "Horse is required") Integer horseId,
            @NotNull(message = "Groom is required") Integer assignedTo,
            @NotNull(message = "Date is required") LocalDate taskDate,
            @NotEmpty(message = "Choose at least one task")
            List<@Pattern(regexp = "FEEDING|CLEANING|BATHING|ICE_BATH",
                    message = "Task must be FEEDING, CLEANING, BATHING or ICE_BATH") String> taskTypes,
            @Size(max = 255, message = "Note must be at most 255 characters") String note) {
    }

    /** created: việc vừa tạo; skipped: loại việc đã có sẵn trong ngày đó nên bỏ qua. */
    public record AssignResult(List<Task> created, List<String> skipped) {
    }
}
