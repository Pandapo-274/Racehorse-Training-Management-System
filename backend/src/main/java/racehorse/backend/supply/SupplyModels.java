package racehorse.backend.supply;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Kiểu dữ liệu của chức năng vật tư (SUPPLY_STOCK). */
public final class SupplyModels {

    private SupplyModels() {
    }

    public record Supply(int supplyId, String supplyName, String category, String unit,
                         BigDecimal minThreshold, BigDecimal currentQty, String area,
                         boolean needsRestock, int updatedBy, String updatedByName, Instant updatedAt) {
    }

    public record SupplyRequest(
            @NotBlank(message = "Name is required") @Size(max = 100, message = "Name must be at most 100 characters")
            String supplyName,
            @NotBlank(message = "Category is required")
            @Pattern(regexp = "FEED|MEDICINE|TOOL", message = "Category must be FEED, MEDICINE or TOOL")
            String category,
            @NotBlank(message = "Unit is required") @Size(max = 20, message = "Unit must be at most 20 characters")
            String unit,
            @NotNull(message = "Minimum is required") @DecimalMin(value = "0", message = "Minimum cannot be negative")
            BigDecimal minThreshold,
            @NotNull(message = "Quantity is required") @DecimalMin(value = "0", message = "Quantity cannot be negative")
            BigDecimal currentQty,
            @Size(max = 100, message = "Area must be at most 100 characters")
            String area) {
    }

    public record CountRequest(
            @NotNull(message = "Quantity is required") @DecimalMin(value = "0", message = "Quantity cannot be negative")
            BigDecimal currentQty) {
    }
}
