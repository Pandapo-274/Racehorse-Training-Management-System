package racehorse.backend.medical;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import racehorse.backend.auth.ApiException;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.UserRepository;
import racehorse.backend.horse.HorseRepository;
import racehorse.backend.horse.HorseResponse;

/**
 * UC18 - Clear Horse Health Status (Recovery Confirmation).
 *
 * Đây là chỗ duy nhất trong hệ thống đặt được HORSE.status = 'ELIGIBLE'. UC14
 * ghi được chẩn đoán và hạ con ngựa xuống MONITOR / INJURED / QUARANTINE,
 * nhưng không nâng lại được - vì nâng lại đòi hai phép kiểm mà một form khám
 * đơn lẻ không làm: không còn chấn thương nào đang điều trị, và không còn
 * lệnh khoá tập nào đang hiệu lực.
 *
 * Bỏ một trong hai phép kiểm sinh ra đúng cái trạng thái mà lệnh khoá tồn tại
 * để ngăn: sổ đăng ký nói con ngựa sẵn sàng tập, trong khi TRAINING_LOCK vẫn
 * còn một dòng ACTIVE cấm nó tập. Huấn luyện viên xếp lịch theo cột status sẽ
 * xếp cho một con ngựa đang bị cấm.
 */
@Service
public class HealthClearanceService {

    private final MedicalRecordRepository records;
    private final HealthClearanceRepository clearance;
    private final InjuryRepository injuries;
    private final TrainingLockRepository locks;
    private final HorseRepository horses;
    private final UserRepository users;

    public HealthClearanceService(MedicalRecordRepository records,
                                  HealthClearanceRepository clearance,
                                  InjuryRepository injuries,
                                  TrainingLockRepository locks,
                                  HorseRepository horses,
                                  UserRepository users) {
        this.records = records;
        this.clearance = clearance;
        this.injuries = injuries;
        this.locks = locks;
        this.horses = horses;
        this.users = users;
    }

    /**
     * Toàn bộ việc nằm trong MỘT giao dịch, chạm bốn bảng: INJURY,
     * TRAINING_LOCK, MEDICAL_RECORD và HORSE. Đây là trường hợp @Transactional
     * đáng giá nhất trong cả backend: khép chấn thương mà không mở được lệnh
     * khoá, hay mở lệnh khoá mà không nâng được status, đều để lại hồ sơ nói
     * một đằng và sổ đăng ký nói một nẻo.
     */
    @Transactional
    public RecoveryConfirmationResponse confirm(RecoveryConfirmationRequest raw,
                                                AuthenticatedUser vet, String ip) {
        HorseResponse horse = horses.findById(raw.horseId())
                .orElseThrow(() -> fieldError("horseId",
                        "Horse #" + raw.horseId() + " does not exist"));

        if ("ELIGIBLE".equals(horse.status())) {
            throw fieldError("horseId", "This horse is already eligible to train");
        }

        /* Trùng id trong danh sách là vô hại nhưng làm số đếm trả về sai, nên
           lọc trước. LinkedHashSet để giữ thứ tự bác sĩ khai. */
        List<Integer> asked = raw.recoveredInjuryIds() == null
                ? List.of()
                : new ArrayList<>(new LinkedHashSet<>(raw.recoveredInjuryIds()));

        validateInjuriesBelongToHorse(asked, horse.horseId());

        /* Khép những chấn thương bác sĩ khai. Dòng nào trả 0 là đã có người
           khép trước - không phải lỗi, nên không đếm vào injuriesClosed. */
        List<Integer> closed = new ArrayList<>();
        for (Integer injuryId : asked) {
            if (injuries.markRecovered(injuryId) == 1) {
                closed.add(injuryId);
            }
        }

        /* Phép kiểm then chốt, và nó chạy SAU khi đã khép: nếu còn bất kỳ chấn
           thương nào chưa RECOVERED thì con ngựa chưa hồi phục, dù bác sĩ có
           khai bao nhiêu id đi nữa. Chạy trước khi khép thì luôn luôn thấy
           "còn chấn thương" và không bao giờ clear được. */
        List<InjuryRepository.OpenInjury> stillOpen =
                injuries.findOpenInjuries(horse.horseId());
        if (!stillOpen.isEmpty()) {
            String list = stillOpen.stream()
                    .map(i -> "#" + i.injuryId() + " " + i.bodyLocation()
                            + " (" + i.severity() + ", " + i.status() + ")")
                    .collect(Collectors.joining("; "));
            throw fieldError("recoveredInjuryIds",
                    "This horse still has unrecovered injuries: " + list);
        }

        List<Integer> lockIds = locks.findActiveLockIds(horse.horseId());
        locks.releaseActiveLocks(horse.horseId(), vet.userId());

        int recordId = records.insertClearance(raw, vet.userId());

        /* Câu UPDATE này là điểm xếp hàng: nó chỉ đổi được nếu con ngựa vẫn
           chưa ELIGIBLE. Hai bác sĩ cùng xác nhận một lần hồi phục thì người
           sau nhận 0 dòng và cả giao dịch - kể cả bản ghi vừa chèn - bị huỷ. */
        if (clearance.clearHorse(horse.horseId()) == 0) {
            throw fieldError("horseId",
                    "Another veterinarian has just cleared this horse");
        }

        users.writeAudit(vet.userId(), "HEALTH_CLEARED", "HORSE", horse.horseId(), "SUCCESS", ip);

        MedicalRecordRow row = records.findById(recordId)
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "The clearance was written but could not be read back"));

        return new RecoveryConfirmationResponse(row, closed, lockIds);
    }

    /**
     * Những gì bác sĩ còn phải xử lý trước khi clear được, để màn hình hiện
     * sẵn thay vì để người dùng bấm rồi mới nhận lỗi.
     */
    public List<InjuryRepository.OpenInjury> openInjuries(int horseId,
                                                                   AuthenticatedUser user) {
        HorseResponse horse = horses.findById(horseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Horse #" + horseId + " does not exist"));
        if ("HORSE_OWNER".equals(user.role()) && horse.ownerId() != user.userId()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You may not view this horse's records");
        }
        return injuries.findOpenInjuries(horseId);
    }

    /* ------------------------------------------------------------------ */

    /**
     * Mỗi injury_id khai phải thuộc đúng con ngựa đang xác nhận.
     *
     * Không kiểm thì một lệnh clear có thể khép chấn thương của con ngựa khác:
     * INJURY không có horse_id, nên id nào cũng "hợp lệ" nếu chỉ nhìn bảng đó.
     * Con ngựa thứ hai sẽ được coi là đã lành mà không ai khám nó.
     */
    private void validateInjuriesBelongToHorse(List<Integer> injuryIds, int horseId) {
        Map<String, String> errors = new HashMap<>();
        for (Integer id : injuryIds) {
            if (id == null) {
                errors.put("recoveredInjuryIds", "Injury id must not be null");
                continue;
            }
            injuries.findHorseOfInjury(id).ifPresentOrElse(owner -> {
                if (owner != horseId) {
                    errors.put("recoveredInjuryIds",
                            "Injury #" + id + " belongs to a different horse");
                }
            }, () -> errors.put("recoveredInjuryIds", "Injury #" + id + " does not exist"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "The recovery could not be confirmed", errors);
        }
    }

    private static ApiException fieldError(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message, Map.of(field, message));
    }
}
