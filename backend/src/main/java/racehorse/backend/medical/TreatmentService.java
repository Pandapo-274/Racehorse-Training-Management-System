package racehorse.backend.medical;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import racehorse.backend.auth.ApiException;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.UserRepository;
import racehorse.backend.horse.HorseRepository;
import racehorse.backend.horse.HorseResponse;
import racehorse.backend.notification.Notice;
import racehorse.backend.notification.NotificationService;

/**
 * UC14 - đơn thuốc kèm một lần khám.
 *
 * Bảng TREATMENT có từ V1 và V3 đã seed dữ liệu thật vào đó, nhưng chưa có
 * dòng code nào đọc hay ghi: một lần khám ghi được chẩn đoán mà không kê được
 * thuốc, và đơn thuốc trong dữ liệu demo thì không màn hình nào thấy.
 *
 * Tách khỏi MedicalRecordService chứ không nhồi thêm vào đó, vì vòng đời khác
 * nhau: một lần khám ghi một lần rồi thôi, còn đơn thuốc được thêm và kết thúc
 * trong suốt quá trình điều trị.
 */
@Service
public class TreatmentService {

    private final TreatmentRepository treatments;
    private final MedicalRecordRepository records;
    private final HorseRepository horses;
    private final UserRepository users;
    private final racehorse.backend.supply.SupplyRepository supplies;
    private final racehorse.backend.expense.ExpenseRepository expenses;
    private final NotificationService notifications;
    private final TrainingLockRepository locks;
    private final racehorse.backend.supply.SupplyService supplyService;

    public TreatmentService(TreatmentRepository treatments, MedicalRecordRepository records,
                            HorseRepository horses, UserRepository users) {
        this(treatments, records, horses, users, null, null, null, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TreatmentService(TreatmentRepository treatments, MedicalRecordRepository records,
                            HorseRepository horses, UserRepository users,
                            racehorse.backend.supply.SupplyRepository supplies,
                            racehorse.backend.expense.ExpenseRepository expenses,
                            NotificationService notifications,
                            TrainingLockRepository locks,
                            @org.springframework.context.annotation.Lazy racehorse.backend.supply.SupplyService supplyService) {
        this.supplyService = supplyService;
        this.locks = locks;
        this.notifications = notifications;
        this.treatments = treatments;
        this.records = records;
        this.horses = horses;
        this.users = users;
        this.supplies = supplies;
        this.expenses = expenses;
    }

    @Transactional
    public TreatmentRow prescribe(TreatmentRequest raw, AuthenticatedUser vet, String ip) {
        /* Chỉ cần biết lần khám có tồn tại; không giữ lại biến vì không dùng
           đến gì khác của nó. Phép kiểm này vẫn cần thiết: FK_TRT_record sẽ
           chặn ở database, nhưng lỗi khoá ngoại nổi lên thành 500 thay vì một
           thông báo 400 gắn đúng vào ô recordId. */
        if (records.findById(raw.recordId()).isEmpty()) {
            throw fieldError("recordId", "Medical record #" + raw.recordId() + " does not exist");
        }

        validate(raw);

        String medName = raw.medicineName();
        java.math.BigDecimal unitPrice = raw.unitPrice();
        Integer supplyId = raw.supplyId();
        racehorse.backend.supply.SupplyModels.Supply foundSupply = null;
        if (supplyId != null && supplies != null) {
            foundSupply = supplies.findById(supplyId)
                    .orElseThrow(() -> fieldError("supplyId", "Medicine not found in supply stock"));
            if (!"MEDICINE".equals(foundSupply.category())) {
                throw fieldError("supplyId", foundSupply.supplyName() + " is not a medicine");
            }
            medName = foundSupply.supplyName();
            if (unitPrice == null || unitPrice.compareTo(java.math.BigDecimal.ZERO) <= 0) {
                unitPrice = foundSupply.unitPrice() != null ? foundSupply.unitPrice() : java.math.BigDecimal.ZERO;
            }
        } else if (supplies != null && medName != null && !medName.isBlank()) {
            List<racehorse.backend.supply.SupplyModels.Supply> list = supplies.find(null, "MEDICINE", false);
            for (racehorse.backend.supply.SupplyModels.Supply item : list) {
                if (item.supplyName().equalsIgnoreCase(medName.trim())) {
                    foundSupply = item;
                    supplyId = item.supplyId();
                    if (unitPrice == null || unitPrice.compareTo(java.math.BigDecimal.ZERO) <= 0) {
                        unitPrice = item.unitPrice() != null ? item.unitPrice() : java.math.BigDecimal.ZERO;
                    }
                    break;
                }
            }
            if (foundSupply == null) {
                String lower = medName.trim().toLowerCase();
                for (racehorse.backend.supply.SupplyModels.Supply item : list) {
                    String itemLower = item.supplyName().toLowerCase();
                    if (itemLower.contains(lower) || lower.contains(itemLower.split(" ")[0])) {
                        foundSupply = item;
                        supplyId = item.supplyId();
                        if (unitPrice == null || unitPrice.compareTo(java.math.BigDecimal.ZERO) <= 0) {
                            unitPrice = item.unitPrice() != null ? item.unitPrice() : java.math.BigDecimal.ZERO;
                        }
                        break;
                    }
                }
            }
        }

        /* Ràng buộc tồn kho: Thuốc phải còn hàng và đủ số lượng trong kho */
        if (foundSupply != null) {
            java.math.BigDecimal stock = foundSupply.currentQty() != null ? foundSupply.currentQty() : java.math.BigDecimal.ZERO;
            if (stock.compareTo(java.math.BigDecimal.ZERO) <= 0) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Medicine \"" + foundSupply.supplyName() + "\" is out of stock (0 " + foundSupply.unit()
                                + "). Please restock before prescribing.");
            }
            java.math.BigDecimal requested = raw.quantity() != null && raw.quantity().compareTo(java.math.BigDecimal.ZERO) > 0
                    ? raw.quantity() : java.math.BigDecimal.ONE;
            if (requested.compareTo(stock) > 0) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Prescribed quantity of \"" + foundSupply.supplyName() + "\" (" + requested.stripTrailingZeros().toPlainString() + " " + foundSupply.unit()
                                + ") exceeds available stock (" + stock.stripTrailingZeros().toPlainString() + " " + foundSupply.unit() + ").");
            }
        }

        String consentStatus = Boolean.TRUE.equals(raw.requiresConsent()) ? "WAITING_CONSENT" : "NOT_REQUIRED";
        int treatmentId = treatments.insert(raw, supplyId, medName, unitPrice, consentStatus);

        autoRecordMedicalExpense(raw, medName, unitPrice, vet.userId());
        deductMedicineSupply(raw, supplyId, treatmentId, vet);

        records.findById(raw.recordId()).ifPresent(rec -> {
            horses.findById(rec.horseId()).ifPresent(h -> {
                if ("ELIGIBLE".equals(h.status())) {
                    records.updateHorseStatus(h.horseId(), "INJURED");
                }
            });
        });
        users.writeAudit(vet.userId(), "TREATMENT_PRESCRIBE", "TREATMENT", treatmentId, "SUCCESS", ip);

        return treatments.findById(treatmentId)
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "The treatment was written but could not be read back"));
    }

    /** Đơn thuốc của một con ngựa, kể cả mục đã hết hạn. */
    public List<TreatmentRow> listForHorse(int horseId, AuthenticatedUser user) {
        HorseResponse horse = horses.findById(horseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Horse #" + horseId + " does not exist"));
        checkCanView(horse, user);
        return treatments.findByHorse(horseId);
    }

    /**
     * Đơn thuốc đang dùng trên toàn đàn. Không có tham số ngựa nên không có phép kiểm
     * quyền sở hữu ở đây: controller đã giới hạn cho nhân sự trong chuồng.
     */
    public List<TreatmentRow> listActive() {
        return treatments.findActive();
    }

    /** Đơn thuốc kê theo một lần khám cụ thể. */
    public List<TreatmentRow> listForRecord(int recordId, AuthenticatedUser user) {
        MedicalRecordRow record = records.findById(recordId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Medical record #" + recordId + " does not exist"));
        horses.findById(record.horseId()).ifPresent(h -> checkCanView(h, user));
        return treatments.findByRecord(recordId);
    }

    /**
     * Kết thúc một mục điều trị hôm nay.
     *
     * Không có đường XOÁ: con ngựa đã thật sự dùng thuốc trong khoảng đó, và
     * xoá dòng đi là viết lại bệnh sử - cùng lý do như UC15 chỉ cho xoá dấu
     * chấn thương còn TREATING.
     */
    @Transactional
    private void autoRecordMedicalExpense(TreatmentRequest raw, String medName,
                                          java.math.BigDecimal unitPrice, int vetId) {
        if (expenses == null || raw.quantity() == null || unitPrice == null
                || unitPrice.compareTo(java.math.BigDecimal.ZERO) <= 0) {
            return;
        }
        java.math.BigDecimal total = raw.quantity().multiply(unitPrice);
        if (total.compareTo(java.math.BigDecimal.ZERO) > 0) {
            records.findById(raw.recordId()).ifPresent(rec -> {
                String unit = "";
                if (raw.supplyId() != null && supplies != null) {
                    unit = supplies.findById(raw.supplyId())
                            .map(racehorse.backend.supply.SupplyModels.Supply::unit).orElse("");
                }
                if ((unit == null || unit.isBlank()) && supplies != null && medName != null) {
                    unit = supplies.find(null, "MEDICINE", false).stream()
                            .filter(s -> s.supplyName().equalsIgnoreCase(medName) ||
                                         medName.toLowerCase().contains(s.supplyName().toLowerCase()) ||
                                         s.supplyName().toLowerCase().contains(medName.toLowerCase()))
                            .map(racehorse.backend.supply.SupplyModels.Supply::unit)
                            .findFirst().orElse("");
                }
                if (unit == null || unit.isBlank()) {
                    if (raw.dosage() != null && !raw.dosage().isBlank()) {
                        String d = raw.dosage().trim();
                        String[] parts = d.split("\\s+", 2);
                        if (parts.length > 1 && !parts[1].isBlank()) {
                            unit = parts[1].trim();
                        } else {
                            unit = d;
                        }
                    }
                }
                if (unit == null || unit.isBlank()) {
                    unit = "dose";
                }

                String qtyStr = raw.quantity().stripTrailingZeros().toPlainString();
                String note = "Prescription: " + (medName != null ? medName : "Medication")
                        + " (" + qtyStr + " " + unit + ")";
                expenses.insert(new racehorse.backend.expense.ExpenseModels.ExpenseRequest(
                        rec.horseId(), "MEDICAL", total, raw.startDate(), note), vetId);
            });
        }
    }

    @Transactional
    public TreatmentRow grantConsent(int id, AuthenticatedUser user, String ip) {
        TreatmentRow t = treatments.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Treatment #" + id + " not found"));
        horses.findById(t.horseId()).ifPresent(h -> checkCanView(h, user));
        if (!"WAITING_CONSENT".equals(t.consentStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "Treatment is not waiting for consent");
        }
        treatments.updateConsent(id, "CONSENTED");
        users.writeAudit(user.userId(), "TREATMENT_CONSENT", "TREATMENT", id, "SUCCESS", ip);
        return treatments.findById(id).orElseThrow();
    }

    @Transactional
    public TreatmentRow rejectConsent(int id, AuthenticatedUser user, String ip) {
        TreatmentRow t = treatments.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Treatment #" + id + " not found"));
        horses.findById(t.horseId()).ifPresent(h -> checkCanView(h, user));
        if (!"WAITING_CONSENT".equals(t.consentStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "Treatment is not waiting for consent");
        }
        treatments.updateConsent(id, "REJECTED");
        users.writeAudit(user.userId(), "TREATMENT_REJECT_CONSENT", "TREATMENT", id, "SUCCESS", ip);
        return treatments.findById(id).orElseThrow();
    }

    public TreatmentRow end(int treatmentId, AuthenticatedUser vet, String ip) {
        TreatmentRow current = treatments.findById(treatmentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Treatment #" + treatmentId + " does not exist"));

        /* Đơn chưa tới ngày bắt đầu thì "kết thúc hôm nay" sẽ cho ngày kết thúc
           trước ngày bắt đầu, vi phạm CK_TRT_dates (trước đây là lỗi 500). */
        if (current.startDate().isAfter(LocalDate.now())) {
            throw new ApiException(HttpStatus.CONFLICT, "This treatment only starts on "
                    + current.startDate() + ", so it cannot end today. Change the prescription instead");
        }
        if (treatments.endToday(treatmentId) == 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This treatment already ended on " + current.endDate());
        }

        int remainingTreatments = treatments.countActiveByHorse(current.horseId());
        boolean hasLock = locks != null && locks.findActiveByHorse(current.horseId()).isPresent();
        if (remainingTreatments == 0 && !hasLock) {
            horses.findById(current.horseId()).ifPresent(h -> {
                if ("INJURED".equals(h.status()) || "MONITOR".equals(h.status())) {
                    records.updateHorseStatus(h.horseId(), "ELIGIBLE");
                }
            });
        }

        users.writeAudit(vet.userId(), "TREATMENT_END", "TREATMENT", treatmentId, "SUCCESS", ip);

        return treatments.findById(treatmentId)
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "The treatment was updated but could not be read back"));
    }

    /* ------------------------------------------------------------------ */

    private void checkCanView(HorseResponse horse, AuthenticatedUser user) {
        if ("HORSE_OWNER".equals(user.role()) && horse.ownerId() != user.userId()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You may not view this horse's records");
        }
    }

    private void validate(TreatmentRequest r) {
        Map<String, String> errors = new HashMap<>();

        /* CK_TRT_dates đã chặn end_date < start_date ở database, nhưng chặn lại
           ở đây để người dùng nhận 400 kèm tên ô sai thay vì 500 từ lỗi ràng
           buộc. */
        if (r.endDate() != null && r.endDate().isBefore(r.startDate())) {
            errors.put("endDate", "End date must be on or after start date");
        }

        /* Mỗi mục phải nói được nó là cái gì. Lược đồ cho medicine_name NULL
           vì không phải mục nào cũng là thuốc ("Chườm lạnh" trong dữ liệu V3),
           nhưng một mục trống cả tên lẫn hướng dẫn thì không ai đọc được là
           phải làm gì. */
        boolean hasName = (r.supplyId() != null) || (r.medicineName() != null && !r.medicineName().isBlank());
        boolean hasInstruction = r.instruction() != null && !r.instruction().isBlank();
        if (!hasName && !hasInstruction) {
            errors.put("medicineName", "Give a medicine name or an instruction, otherwise "
                    + "nobody can tell what this treatment is");
        }

        /* Ngày bắt đầu quá xa trong tương lai thường là lỗi gõ năm. Cho phép
           kê trước vài ngày (bác sĩ lên đơn cho đợt tới) nhưng không cho cả
           năm sau. */
        if (r.startDate().isAfter(LocalDate.now().plusDays(30))) {
            errors.put("startDate", "Start date is more than 30 days away, check the year");
        }

        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The treatment could not be saved", errors);
        }
    }

    private static ApiException fieldError(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message, Map.of(field, message));
    }

    private void deductMedicineSupply(TreatmentRequest raw, Integer supplyId, int treatmentId, AuthenticatedUser vet) {
        if (supplyId == null || raw.quantity() == null || raw.quantity().compareTo(java.math.BigDecimal.ZERO) <= 0) {
            return;
        }

        String horseName = records.findById(raw.recordId())
                .flatMap(rec -> horses.findById(rec.horseId()))
                .map(HorseResponse::horseName)
                .orElse("Horse");
        String reason = "Prescription for " + horseName + ": " + raw.quantity() + " "
                + (raw.dosage() != null ? raw.dosage() : "");

        if (supplyService != null) {
            supplyService.consume(supplyId, raw.quantity(), "TREATMENT", treatmentId, reason, vet);
        } else if (supplies != null) {
            supplies.takeUpTo(supplyId, raw.quantity(), vet.userId()).ifPresent(range -> {
                java.math.BigDecimal taken = range[0].subtract(range[1]);
                if (taken.signum() > 0) {
                    supplies.insertMovement(supplyId, "CONSUME", taken.negate(), range[1],
                            "TREATMENT", treatmentId, reason, vet.userId());
                }
            });
        }
    }
}
