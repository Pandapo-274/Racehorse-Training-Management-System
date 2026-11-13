package racehorse.backend.entity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import racehorse.backend.repository.RaceRepository;

/**
 * Flow 4 - chăm sóc chuồng trại và dinh dưỡng hằng ngày.
 * Hai bảng có từ V1: FEEDING_SCHEDULE (khẩu phần đã duyệt) và DAILY_TASK (việc trong ngày).
 */
public final class CareModels {

    private CareModels() {
    }

    /**
     * Một dòng khẩu phần: một loại thức ăn trong một bữa.
     * supplyId (V21): the stock item it is taken from; null = a line written
     * before V21 that could not be linked - it is not taken from the store until
     * the Head Trainer picks the item again. feedType and unit are copies of the
     * stock item's name and unit, kept so older screens still read them.
     */
    public record FeedingItem(
            int feedingId, int horseId, String horseName, String stallNo,
            String mealTime, String feedType, BigDecimal quantity, String unit,
            Integer approvedBy, String approvedByName, String note, Integer supplyId) {
    }

    /** Thay toàn bộ khẩu phần của một con ngựa (Head Trainer hoặc Vet duyệt). */
    public record RationRequest(
            @NotEmpty(message = "A ration needs at least one item")
            @Size(max = 15, message = "At most 15 items")
            @Valid List<@NotNull RationItem> items) {
    }

    /**
     * One line of a new ration. supplyId is required (checked in the service so
     * the error names the line); feedType and unit are taken from the stock item
     * and whatever the client sends for them is ignored.
     */
    public record RationItem(
            @NotBlank(message = "Meal is required")
            @Pattern(regexp = "MORNING|NOON|EVENING", message = "Meal must be MORNING, NOON or EVENING")
            String mealTime,
            Integer supplyId,
            @Size(max = 50, message = "Feed name must be at most 50 characters")
            String feedType,
            @NotNull(message = "Quantity is required")
            @DecimalMin(value = "0.01", message = "Quantity must be greater than 0")
            BigDecimal quantity,
            @Size(max = 20, message = "Unit must be at most 20 characters")
            String unit,
            @Size(max = 255, message = "Note must be at most 255 characters")
            String note) {

        /** The same line with name and unit copied from the stock item. */
        public RationItem withStock(int id, String name, String stockUnit) {
            String n = name.length() <= 50 ? name : name.substring(0, 50); // FEEDING_SCHEDULE.feed_type is 50
            return new RationItem(mealTime, id, n, quantity, stockUnit, note);
        }
    }

    /**
     * Một việc chăm sóc trong ngày.
     * completedBy (V25): who ticked it - a Head Trainer may tick for a groom.
     * state: OPEN, DONE, DONE_LATE (ticked after its day), MISSED (its day has
     * passed and it is still open). Derived on read, like RaceRepository.stateOf.
     */
    public record Task(
            int taskId, int horseId, String horseName, String stallNo,
            String taskType, int assignedTo, String assignedToName,
            LocalDate taskDate, boolean completed, Instant completedAt, String note,
            Integer completedBy, String completedByName, String state) {
    }

    /** Hand an open task to another groom (#15). */
    public record ReassignRequest(@NotNull(message = "Groom is required") Integer assignedTo) {
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

    /**
     * Giao việc hàng loạt: nhiều ngựa, từ ngày fromDate tới toDate (tối đa 14 ngày),
     * cho một Groom. Dùng cho việc lặp lại như dọn chuồng mỗi sáng cả dãy chuồng:
     * giao từng con từng ngày thì 20 con x 7 ngày là 140 lần bấm.
     */
    public record BulkAssignRequest(
            @NotEmpty(message = "Choose at least one horse")
            @Size(max = 80, message = "At most 80 horses at once")
            List<@NotNull Integer> horseIds,
            @NotNull(message = "Groom is required") Integer assignedTo,
            @NotNull(message = "Start date is required") LocalDate fromDate,
            @NotNull(message = "End date is required") LocalDate toDate,
            @NotEmpty(message = "Choose at least one task")
            List<@Pattern(regexp = "FEEDING|CLEANING|BATHING|ICE_BATH",
                    message = "Task must be FEEDING, CLEANING, BATHING or ICE_BATH") String> taskTypes,
            @Size(max = 255, message = "Note must be at most 255 characters") String note) {
    }

    /**
     * created / skipped: số việc vừa tạo / bỏ qua vì con ngựa đã có việc loại đó trong ngày.
     * notes: ngựa bị bỏ qua (không ở chuồng) và cảnh báo quá tải - không chặn.
     */
    public record BulkAssignResult(int created, int skipped, List<String> notes) {
    }

    /**
     * created: việc vừa tạo; skipped: loại việc đã có sẵn trong ngày đó nên bỏ qua.
     * warnings: does not block - e.g. the groom already has many tasks that day.
     */
    public record AssignResult(List<Task> created, List<String> skipped, List<String> warnings) {
    }
}
