package racehorse.backend.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/*
 * Một đơn thuốc / lần điều trị (bảng TREATMENT) đã ghép tên ngựa.
 *   active      - đơn còn hiệu lực (chưa tới end_date), tính lúc đọc.
 *   totalCost   - quantity x unitPrice, sinh chi phí MEDICAL cho chủ ngựa.
 *   requiresConsent / consentStatus - điều trị lớn phải được chủ ngựa đồng ý
 *                 (NOT_REQUIRED | WAITING_CONSENT | CONSENTED | REJECTED).
 */
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
