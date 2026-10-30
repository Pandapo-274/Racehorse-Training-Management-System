package racehorse.backend.expense;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Chi phí nuôi ngựa: bảng EXPENSE_LOG (có từ V1).
 *
 * Trước đây bảng này chỉ có dữ liệu mẫu của V3 và không có đường nào ghi thêm,
 * nên báo cáo chi phí của chủ ngựa không bao giờ đổi. Club Manager ghi chi phí
 * ở trang Costs; báo cáo của chủ ngựa (OwnerDashboardService) và thẻ chi phí
 * trên trang tổng quan của Manager đọc lại đúng bảng này.
 */
public final class ExpenseModels {

    private ExpenseModels() {
    }

    /** Bốn hạng mục, khớp CK_EXP_cat. */
    public static final List<String> CATEGORIES = List.of("FEEDING", "MEDICAL", "TRAINING", "OTHER");

    /** Một khoản chi, như frontend nhìn thấy. */
    public record Expense(int expenseId, int horseId, String horseName, String registrationCode,
                          String category, BigDecimal amount, LocalDate expenseDate,
                          Integer recordedBy, String recordedByName, String note) {
    }

    /**
     * Danh sách theo bộ lọc. totals và total tính trên MỌI dòng khớp bộ lọc,
     * không chỉ những dòng trả về: truncated = true nghĩa là items bị cắt bớt.
     */
    public record ExpenseList(List<Expense> items, Map<String, BigDecimal> totals,
                              BigDecimal total, int count, boolean truncated) {
    }

    public record ExpenseRequest(
            @NotNull(message = "Choose a horse")
            Integer horseId,

            @NotBlank(message = "Choose a category")
            @Pattern(regexp = "FEEDING|MEDICAL|TRAINING|OTHER",
                     message = "Category must be FEEDING, MEDICAL, TRAINING or OTHER")
            String category,

            @NotNull(message = "Enter the amount")
            @DecimalMin(value = "1", message = "Amount must be at least 1")
            @Digits(integer = 10, fraction = 2, message = "Amount is too large")
            BigDecimal amount,

            @NotNull(message = "Enter the date")
            @PastOrPresent(message = "The date cannot be in the future")
            LocalDate expenseDate,

            @Size(max = 255, message = "Note must be at most 255 characters")
            String note
    ) {
    }
}
