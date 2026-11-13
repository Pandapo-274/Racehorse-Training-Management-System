package racehorse.backend.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record TreatmentRow(
        int treatmentId,
        int recordId,
        int horseId,
        String horseName,
        String medicineName,
        String dosage,
        String frequency,
        String instruction,
        LocalDate startDate,
        LocalDate endDate,
        boolean active,
        /* V28: Đơn giá và vật tư */
        Integer supplyId,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal totalCost,
        boolean requiresConsent,
        String consentStatus,
        Instant consentedAt
) {
    /** Overloaded constructor for backward compatibility. */
    public TreatmentRow(int treatmentId, int recordId, int horseId, String horseName,
                        String medicineName, String dosage, String frequency, String instruction,
                        LocalDate startDate, LocalDate endDate, boolean active) {
        this(treatmentId, recordId, horseId, horseName, medicineName, dosage, frequency, instruction,
                startDate, endDate, active, null, null, null, null, false, "NOT_REQUIRED", null);
    }
}
