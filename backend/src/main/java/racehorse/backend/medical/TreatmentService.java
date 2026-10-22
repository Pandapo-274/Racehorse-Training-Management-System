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

    public TreatmentService(TreatmentRepository treatments, MedicalRecordRepository records,
                            HorseRepository horses, UserRepository users) {
        this.treatments = treatments;
        this.records = records;
        this.horses = horses;
        this.users = users;
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

        int treatmentId = treatments.insert(raw);
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
    public TreatmentRow end(int treatmentId, AuthenticatedUser vet, String ip) {
        TreatmentRow current = treatments.findById(treatmentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Treatment #" + treatmentId + " does not exist"));

        if (treatments.endToday(treatmentId) == 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This treatment already ended on " + current.endDate());
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
        boolean hasName = r.medicineName() != null && !r.medicineName().isBlank();
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
}
