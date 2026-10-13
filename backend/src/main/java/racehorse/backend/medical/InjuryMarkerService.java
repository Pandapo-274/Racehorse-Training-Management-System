package racehorse.backend.medical;

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
 * UC15 - Map Injury on 3D Anatomy Model (3D Injury Marker).
 *
 * Phạm vi hẹp có chủ đích: UC15 chỉ GHIM dấu lên hồ sơ khám. Nó KHÔNG đổi
 * HORSE.status (việc của UC14) và KHÔNG ra lệnh khoá tập (việc của UC16).
 *
 * Lý do giữ hẹp cũng là lý do UC14 không được đặt ELIGIBLE: mỗi phép đổi
 * trạng thái có một bộ kiểm riêng, và cho một use case thứ hai cũng đổi được
 * là mở một đường đi vòng qua bộ kiểm ấy. Ở đây cụ thể là: nếu ghim dấu cũng
 * tự hạ con ngựa xuống INJURED thì một dấu ghim sai chỗ, xoá đi rồi, vẫn để
 * lại con ngựa ở trạng thái INJURED mà không còn chấn thương nào giải thích -
 * và UC18 sẽ clear nó mà không kiểm được gì, vì chẳng còn gì để kiểm.
 */
@Service
public class InjuryMarkerService {

    private final InjuryRepository injuries;
    private final MedicalRecordRepository records;
    private final HorseRepository horses;
    private final UserRepository users;

    public InjuryMarkerService(InjuryRepository injuries, MedicalRecordRepository records,
                               HorseRepository horses, UserRepository users) {
        this.injuries = injuries;
        this.records = records;
        this.horses = horses;
        this.users = users;
    }

    @Transactional
    public InjuryMarkerRow mark(InjuryMarkerRequest raw, AuthenticatedUser vet, String ip) {
        MedicalRecordRow record = records.findById(raw.recordId())
                .orElseThrow(() -> fieldError("recordId",
                        "Medical record #" + raw.recordId() + " does not exist"));

        validate(raw, record);

        int injuryId = injuries.insertMarker(raw);

        users.writeAudit(vet.userId(), "INJURY_MARKED", "INJURY", injuryId, "SUCCESS", ip);

        return injuries.findById(injuryId)
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "The marker was written but could not be read back"));
    }

    /** Mọi dấu của một con ngựa, để mô hình giải phẫu vẽ lên. */
    public List<InjuryMarkerRow> listForHorse(int horseId, AuthenticatedUser user) {
        HorseResponse horse = horses.findById(horseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Horse #" + horseId + " does not exist"));
        if ("HORSE_OWNER".equals(user.role()) && horse.ownerId() != user.userId()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You may not view this horse's records");
        }
        return injuries.findByHorse(horseId);
    }

    /**
     * Xoá một dấu ghim sai chỗ.
     *
     * Hai phép kiểm, và cả hai đều có một lỗi 500 tương ứng nếu bỏ đi:
     *   - lệnh khoá đang trỏ vào dấu này thì FK_LOCK_injury chặn câu DELETE,
     *     và lỗi khoá ngoại nổi lên thành "lỗi hệ thống";
     *   - dấu không còn TREATING thì câu DELETE trả 0 dòng, và nếu không phân
     *     biệt thì người dùng nhận 204 cho một việc chưa hề xảy ra.
     */
    @Transactional
    public void unmark(int injuryId, AuthenticatedUser vet, String ip) {
        injuries.findById(injuryId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Injury marker #" + injuryId + " does not exist"));

        if (injuries.isReferencedByLock(injuryId)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "A training lock refers to this injury. Release the lock before removing the marker");
        }

        if (injuries.deleteMarker(injuryId) == 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Only a marker still under treatment can be removed. "
                            + "This one has already been assessed");
        }

        users.writeAudit(vet.userId(), "INJURY_UNMARKED", "INJURY", injuryId, "SUCCESS", ip);
    }

    /* ------------------------------------------------------------------ */

    private void validate(InjuryMarkerRequest r, MedicalRecordRow record) {
        Map<String, String> errors = new HashMap<>();

        /* Dấu chấn thương chỉ được ghim lên hồ sơ đã XÁC NHẬN có chấn thương.
           Không kiểm thì một dấu có thể treo vào hồ sơ tẩy giun định kỳ, và
           cái dấu đó sẽ chặn UC18 clear con ngựa vì một chấn thương mà không
           lần khám nào từng chẩn đoán. */
        if (!record.injuryConfirmed()) {
            errors.put("recordId",
                    "That medical record does not confirm an injury. "
                            + "Record the diagnosis with injuryConfirmed first");
        }

        /* Toạ độ đi theo cặp. Một mình x hoặc một mình y thì mô hình không vẽ
           được dấu vào đâu, mà database vẫn nhận vì cả hai cột đều NULL được. */
        boolean hasX = r.coordinateX() != null;
        boolean hasY = r.coordinateY() != null;
        if (hasX != hasY) {
            errors.put(hasX ? "coordinateY" : "coordinateX",
                    "Coordinates must be given as a pair, or left out entirely");
        }

        if (r.bodyLocation() != null && !r.bodyLocation().isBlank()
                && injuries.hasOpenMarkerAt(r.recordId(), r.bodyLocation())) {
            errors.put("bodyLocation",
                    "This record already has an open injury at " + r.bodyLocation().trim());
        }

        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The marker could not be saved", errors);
        }
    }

    private static ApiException fieldError(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message, Map.of(field, message));
    }
}
