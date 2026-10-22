package racehorse.backend.medical;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
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
 * UC16 - Issue Emergency Training Block (Training Lock Management).
 *
 * Lệnh khoá là một chốt an toàn, và nó được thiết kế theo đúng cách chốt an
 * toàn hoạt động: DỄ bật, KHÓ tắt.
 *
 * Bật dễ - huấn luyện viên trưởng, bác sĩ hay quản lý đều ra lệnh được, không
 * cần chẩn đoán trước, vì người đứng cạnh con ngựa là người thấy nó đi cà
 * nhắc trước tiên và khoảng thời gian chờ bác sĩ là đúng lúc nguy hiểm nhất.
 *
 * Tắt khó - chỉ bác sĩ và quản lý mở được, và không mở được lệnh nào đang có
 * một chấn thương chưa lành làm căn cứ. Nếu huấn luyện viên tự mở được lệnh
 * mà bác sĩ vừa ra, thì dưới áp lực phải cho ngựa chạy, cái chốt này không
 * còn chặn được gì nữa - đó đúng là tình huống nó sinh ra để chặn.
 *
 * UC16 KHÔNG đổi HORSE.status. Trạng thái sức khoẻ do UC14 ghi và UC18 mở, và
 * một lệnh khoá không phải một chẩn đoán - khoá khẩn cấp thường ra trước khi
 * có ai khám. Bảng điều khiển không bị mâu thuẫn vì nó vốn đọc lệnh khoá
 * riêng: TrainerDashboardService đã đếm locked_horses và lấy lock_id cho từng
 * con, độc lập với cột status.
 */
@Service
public class TrainingLockService {

    private final TrainingLockRepository locks;
    private final InjuryRepository injuries;
    private final HorseRepository horses;
    private final UserRepository users;
    private final NotificationService notifications;

    public TrainingLockService(TrainingLockRepository locks, InjuryRepository injuries,
                               HorseRepository horses, UserRepository users,
                               NotificationService notifications) {
        this.locks = locks;
        this.injuries = injuries;
        this.horses = horses;
        this.users = users;
        this.notifications = notifications;
    }

    @Transactional
    public TrainingLockRow issue(TrainingLockRequest raw, AuthenticatedUser issuer, String ip) {
        HorseResponse horse = horses.findById(raw.horseId())
                .orElseThrow(() -> fieldError("horseId",
                        "Horse #" + raw.horseId() + " does not exist"));

        validate(raw, horse);

        int lockId;
        try {
            lockId = locks.insert(raw, issuer.userId());
        } catch (DuplicateKeyException e) {
            /* UX_LOCK_active là chỉ mục duy nhất có điều kiện trên
               (horse_id) WHERE status = 'ACTIVE'. Phép kiểm trong validate()
               lo trường hợp thường và cho ra thông báo tử tế, nhưng giữa lúc
               kiểm và lúc chèn vẫn có một khe: hai người cùng khoá một con
               ngựa thì người sau đụng chỉ mục. Không bắt ở đây thì lỗi trùng
               khoá nổi lên thành 500, và người dùng nhận "lỗi hệ thống" cho
               một việc có lý do rõ ràng. */
            throw new ApiException(HttpStatus.CONFLICT,
                    "This horse has just been locked by someone else");
        }

        users.writeAudit(issuer.userId(), "TRAINING_LOCK_ISSUED", "TRAINING_LOCK", lockId,
                "SUCCESS", ip);

        TrainingLockRow issued = locks.findById(lockId)
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "The lock was issued but could not be read back"));

        /* Huấn luyện viên trưởng là người xếp lịch, nên phải biết ngay con
           ngựa không được tập nữa; chủ ngựa thì có quyền biết ngựa mình bị
           dừng. Người ra lệnh không cần chuông báo cho chính mình. */
        notifications.notifyHorseWatchers(horse.ownerId(), issuer, new Notice(
                "TRAINING_LOCK_ISSUED",
                "Training lock issued",
                horse.horseName() + " is locked (" + issued.lockLevel() + "): " + issued.reason(),
                Notice.HORSE, horse.horseId()));
        return issued;
    }

    /**
     * Mở một lệnh khoá bằng tay.
     *
     * Mở lệnh KHÔNG phải là trả con ngựa về diện sẵn sàng tập. HORSE.status
     * không đổi ở đây; nâng nó lên ELIGIBLE vẫn là việc của UC18 và vẫn phải
     * qua phép kiểm chấn thương ở đó. Hai việc này hay bị lẫn vì UC18 cũng mở
     * lệnh khoá - nhưng UC18 mở lệnh NHƯ MỘT PHẦN của việc xác nhận hồi phục,
     * còn đây là mở một lệnh ra sai hoặc đã hết lý do.
     */
    @Transactional
    public TrainingLockRow release(int lockId, AuthenticatedUser user, String ip) {
        TrainingLockRow lock = locks.findById(lockId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Training lock #" + lockId + " does not exist"));

        if (!"ACTIVE".equals(lock.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "This lock has already been released");
        }

        /* Lệnh khoá có căn cứ là một chấn thương CHƯA lành thì không mở bằng
           tay được. Lệnh ấy đang đúng về mặt y tế; đường ra của nó là UC18,
           nơi việc mở lệnh nằm trong cùng một giao dịch với việc khép chấn
           thương và nâng trạng thái. Mở riêng ở đây là tháo chốt mà để nguyên
           cái lý do người ta lắp chốt. */
        if (lock.injuryId() != null && !"RECOVERED".equals(lock.injuryStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This lock is based on injury #" + lock.injuryId()
                            + ", which is still " + lock.injuryStatus()
                            + ". Confirm the recovery instead of releasing the lock on its own");
        }

        if (locks.release(lockId, user.userId()) == 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This lock has just been released by someone else");
        }

        users.writeAudit(user.userId(), "TRAINING_LOCK_RELEASED", "TRAINING_LOCK", lockId,
                "SUCCESS", ip);

        TrainingLockRow released = locks.findById(lockId)
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "The lock was released but could not be read back"));

        horses.findById(lock.horseId()).ifPresent(horse -> notifications.notifyHorseWatchers(
                horse.ownerId(), user,
                new Notice(
                        "TRAINING_LOCK_RELEASED",
                        "Training lock released",
                        horse.horseName() + " is no longer locked. Its health status is still "
                                + horse.status() + ".",
                        Notice.HORSE, horse.horseId())));
        return released;
    }

    /** Màn Training Lock Management: mọi lệnh đang hiệu lực trong chuồng. */
    public List<TrainingLockRow> listActive() {
        return locks.findAllActive();
    }

    /** Bệnh sử khoá tập của một con ngựa. */
    public List<TrainingLockRow> listForHorse(int horseId, AuthenticatedUser user) {
        HorseResponse horse = horses.findById(horseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Horse #" + horseId + " does not exist"));
        if ("HORSE_OWNER".equals(user.role()) && horse.ownerId() != user.userId()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You may not view this horse's records");
        }
        return locks.findByHorse(horseId);
    }

    /* ------------------------------------------------------------------ */

    private void validate(TrainingLockRequest r, HorseResponse horse) {
        Map<String, String> errors = new HashMap<>();

        /* Con ngựa đã có lệnh ACTIVE thì không ra lệnh thứ hai. Nói rõ lệnh
           đang có là của ai, vì việc người dùng cần làm tiếp là đổi mức khoá
           của lệnh cũ hoặc nói chuyện với người ra lệnh, không phải thử lại. */
        locks.findActiveByHorse(horse.horseId()).ifPresent(active ->
                errors.put("horseId", "This horse is already locked by "
                        + active.issuedByName() + " (" + active.lockLevel() + "): "
                        + active.reason()));

        /* Chấn thương làm căn cứ phải thuộc đúng con ngựa này. INJURY không
           có horse_id nên nếu không nối để kiểm thì id nào cũng trông hợp lệ,
           và lệnh khoá sẽ dẫn chiếu tới chấn thương của một con khác - khi
           UC18 đọc injuryStatus để quyết định có cho mở lệnh hay không, nó sẽ
           đọc tình trạng của con ngựa sai. */
        if (r.injuryId() != null) {
            injuries.findById(r.injuryId()).ifPresentOrElse(injury -> {
                if (injury.horseId() != horse.horseId()) {
                    errors.put("injuryId", "Injury #" + r.injuryId()
                            + " belongs to a different horse");
                } else if ("RECOVERED".equals(injury.status())) {
                    errors.put("injuryId", "Injury #" + r.injuryId()
                            + " has already recovered and cannot justify a lock");
                }
            }, () -> errors.put("injuryId", "Injury #" + r.injuryId() + " does not exist"));
        }

        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The lock could not be issued", errors);
        }
    }

    private static ApiException fieldError(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message, Map.of(field, message));
    }
}
