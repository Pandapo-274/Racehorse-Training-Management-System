package racehorse.backend.entity;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Kiểu dữ liệu của chức năng vật tư (SUPPLY_STOCK). */
public final class SupplyModels {

    private SupplyModels() {
    }

    /**
     * expiryDate (V24): only meaningful for MEDICINE; null = not tracked.
     * expired: expiryDate is before today (computed in the query, not stored).
     */
    public record Supply(int supplyId, String supplyName, String category, String unit,
                         BigDecimal minThreshold, BigDecimal currentQty, String area,
                         boolean needsRestock, int updatedBy, String updatedByName, Instant updatedAt,
                         LocalDate expiryDate, boolean expired, BigDecimal unitPrice) {
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
            /* Opening quantity, used by CREATE only. An edit (PUT /{id}) ignores it:
               after V21 every change to the quantity goes through the movement
               ledger - a count, a delivery, or a task. */
            @DecimalMin(value = "0", message = "Quantity cannot be negative")
            BigDecimal currentQty,
            @Size(max = 100, message = "Area must be at most 100 characters")
            String area,
            /* Medicine only (V24). */
            LocalDate expiryDate,
            /* V28: Unit price for stock items */
            BigDecimal unitPrice) {
    }

    /** A delivery into the store (POST /{id}/receive). */
    public record ReceiveRequest(
            @NotNull(message = "Quantity is required")
            @DecimalMin(value = "0.01", message = "Quantity must be greater than 0")
            BigDecimal qty,
            @Size(max = 255, message = "Note must be at most 255 characters")
            String note,
            BigDecimal unitPrice,
            String unit,
            String area) {
        public ReceiveRequest(BigDecimal qty, String note) {
            this(qty, note, null, null, null);
        }
    }

    /** Stock thrown away: spoiled feed, expired medicine, broken tools (POST /{id}/waste). */
    public record WasteRequest(
            @NotNull(message = "Quantity is required")
            @DecimalMin(value = "0.01", message = "Quantity must be greater than 0")
            BigDecimal qty,
            @NotBlank(message = "Reason is required")
            @Size(max = 255, message = "Reason must be at most 255 characters")
            String reason) {
    }

    /** One line of the stock ledger (SUPPLY_MOVEMENT, V21). */
    public record Movement(int movementId, int supplyId, String movementType, BigDecimal qtyDelta,
                           BigDecimal qtyAfter, String refType, Integer refId, String reason,
                           int createdBy, String createdByName, Instant createdAt) {
    }

    public record CountRequest(
            @NotNull(message = "Quantity is required") @DecimalMin(value = "0", message = "Quantity cannot be negative")
            BigDecimal currentQty) {
    }
}
