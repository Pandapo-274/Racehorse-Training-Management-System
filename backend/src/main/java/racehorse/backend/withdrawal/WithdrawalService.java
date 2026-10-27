package racehorse.backend.withdrawal;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import racehorse.backend.auth.ApiException;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.UserRepository;
import racehorse.backend.horse.HorsePolicy;
import racehorse.backend.horse.HorseRepository;
import racehorse.backend.horse.HorseResponse;
import racehorse.backend.notification.Notice;
import racehorse.backend.notification.NotificationService;
import racehorse.backend.training.TrainingPlanRepository;
import racehorse.backend.training.TrainingSessionRepository;

/**
 * Rút ngựa khỏi học viện: chủ ngựa gửi yêu cầu, Club Manager duyệt.
 *
 * Duyệt làm năm việc trong MỘT giao dịch, để không bao giờ có con ngựa đã rút
 * mà vẫn còn buổi tập trên lịch Groom, hay giáo án còn ACTIVE:
 *   1. huỷ các buổi tập còn SCHEDULED;
 *   2. kết thúc mọi giáo án chưa COMPLETED;
 *   3. trả chuồng và đánh dấu ngựa WITHDRAWN;
 *   4. đóng yêu cầu;
 *   5. báo Groom có buổi bị huỷ, chủ ngựa và Head Trainer.
 */
@Service
public class WithdrawalService {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH);

    private final WithdrawalRepository withdrawals;
    private final HorseRepository horses;
    private final TrainingSessionRepository sessions;
    private final TrainingPlanRepository plans;
    private final UserRepository users;
    private final NotificationService notifications;

    public WithdrawalService(WithdrawalRepository withdrawals, HorseRepository horses,
                             TrainingSessionRepository sessions, TrainingPlanRepository plans,
                             UserRepository users, NotificationService notifications) {
        this.withdrawals = withdrawals;
        this.horses = horses;
        this.sessions = sessions;
        this.plans = plans;
        this.users = users;
        this.notifications = notifications;
    }

    /* ------------------------------------------------------------------ *
     * Chủ ngựa
     * ------------------------------------------------------------------ */

    @Transactional
    public WithdrawalRow submit(WithdrawalSubmitRequest raw, AuthenticatedUser me, String ip) {
        HorseResponse horse = horses.findById(raw.horseId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Horse #" + raw.horseId() + " does not exist"));
        if (horse.ownerId() != me.userId()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only withdraw your own horses");
        }
        if (HorsePolicy.PEDIGREE.equals(horse.recordType())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "A pedigree record is not kept at the club, so there is nothing to withdraw");
        }
        if (horse.isWithdrawn()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    horse.horseName() + " has already left the club");
        }
        if (withdrawals.hasOpenRequest(horse.horseId())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "A withdrawal request for this horse is already waiting for the club");
        }

        int id;
        try {
            id = withdrawals.insert(horse.horseId(), me.userId(), raw.reason().trim());
        } catch (DataIntegrityViolationException e) {
            // UX_HWD_open: hai lần bấm gần như cùng lúc lọt qua phép kiểm ở trên.
            throw new ApiException(HttpStatus.CONFLICT,
                    "A withdrawal request for this horse is already waiting for the club");
        }
        users.writeAudit(me.userId(), "HORSE_WITHDRAWAL_SUBMIT", "HORSE_WITHDRAWAL", id, "SUCCESS", ip);

        WithdrawalRow saved = getOrThrow(id);
        notifications.notifyRole("CLUB_MANAGER", me, new Notice(
                "HORSE_WITHDRAWAL_SUBMITTED",
                "Horse withdrawal request",
                saved.requesterName() + " asked to withdraw " + saved.horseName() + " from the club.",
                Notice.HORSE_WITHDRAWAL, id));
        return saved;
    }

    /** Chủ ngựa đổi ý khi yêu cầu còn đang chờ. */
    @Transactional
    public WithdrawalRow cancel(int withdrawalId, AuthenticatedUser me, String ip) {
        WithdrawalRow row = getOrThrow(withdrawalId);
        HorseResponse horse = horseOf(row);
        if (horse.ownerId() != me.userId()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This request is about another owner's horse");
        }
        requirePending(row);
        if (withdrawals.close(withdrawalId, "CANCELLED", me.userId(), null) == 0) {
            throw alreadyHandled();
        }
        users.writeAudit(me.userId(), "HORSE_WITHDRAWAL_CANCEL", "HORSE_WITHDRAWAL",
                withdrawalId, "SUCCESS", ip);
        return getOrThrow(withdrawalId);
    }

    /* ------------------------------------------------------------------ *
     * Xem
     * ------------------------------------------------------------------ */

    /** Chủ ngựa chỉ thấy yêu cầu về ngựa của mình; Club Manager thấy tất cả. */
    public List<WithdrawalRow> list(Integer horseId, AuthenticatedUser me) {
        Integer ownerId = "HORSE_OWNER".equals(me.role()) ? me.userId() : null;
        return withdrawals.find(ownerId, horseId);
    }

    /* ------------------------------------------------------------------ *
     * Club Manager
     * ------------------------------------------------------------------ */

    @Transactional
    public WithdrawalRow approve(int withdrawalId, WithdrawalReviewRequest review,
                                 AuthenticatedUser me, String ip) {
        WithdrawalRow row = getOrThrow(withdrawalId);
        requirePending(row);
        HorseResponse horse = horseOf(row);
        int horseId = horse.horseId();

        /* Buổi tập đang chạy thì không cắt ngang: Groom đang dắt ngựa trên
           đường đua và còn phải nhập số đo. Đợi buổi đó kết thúc rồi duyệt. */
        if (sessions.hasRunningSession(horseId)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    horse.horseName() + " is in a training session right now. "
                            + "Approve after the session ends.");
        }

        // Đọc trước khi huỷ để biết báo cho Groom nào, về buổi nào.
        List<TrainingSessionRepository.ScheduledSlot> slots = sessions.findScheduledByHorse(horseId);
        int cancelled = sessions.cancelScheduledByHorse(horseId);
        int ended = plans.completeOpenPlansByHorse(horseId);

        String releasedStall = horse.stallNo();
        if (horses.markWithdrawn(horseId) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, horse.horseName() + " has already left the club");
        }

        String note = review == null ? null : blankToNull(review.note());
        if (withdrawals.markApproved(withdrawalId, me.userId(), note,
                releasedStall, cancelled, ended) == 0) {
            // @Transactional cuốn mọi thứ ở trên đi cùng.
            throw alreadyHandled();
        }

        users.writeAudit(me.userId(), "HORSE_WITHDRAWAL_APPROVE", "HORSE_WITHDRAWAL",
                withdrawalId, "SUCCESS", ip);
        users.writeAudit(me.userId(), "HORSE_WITHDRAW", "HORSE", horseId, "SUCCESS", ip);
        if (cancelled > 0) {
            users.writeAudit(me.userId(), "TRAINING_SESSION_CANCEL_BULK", "HORSE", horseId, "SUCCESS", ip);
        }
        if (ended > 0) {
            users.writeAudit(me.userId(), "TRAINING_PLAN_COMPLETE_BULK", "HORSE", horseId, "SUCCESS", ip);
        }

        notifyGrooms(horse.horseName(), slots);

        notifications.notifyUser(horse.ownerId(), new Notice(
                "HORSE_WITHDRAWAL_APPROVED",
                "Horse withdrawal approved",
                horse.horseName() + " has been withdrawn from the club."
                        + (note == null ? "" : " " + note),
                Notice.HORSE, horseId));

        notifications.notifyRole("HEAD_TRAINER", me, new Notice(
                "HORSE_WITHDRAWN",
                "Horse withdrawn from the club",
                horse.horseName() + " has left the club. "
                        + plural(ended, "training plan") + " ended, "
                        + plural(cancelled, "scheduled session") + " cancelled.",
                Notice.HORSE, horseId));

        return getOrThrow(withdrawalId);
    }

    @Transactional
    public WithdrawalRow reject(int withdrawalId, WithdrawalReviewRequest review,
                                AuthenticatedUser me, String ip) {
        WithdrawalRow row = getOrThrow(withdrawalId);
        requirePending(row);

        String note = review == null ? null : blankToNull(review.note());
        if (note == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Say why the request is being turned down",
                    Map.of("note", "A reason is required when rejecting"));
        }
        if (withdrawals.close(withdrawalId, "REJECTED", me.userId(), note) == 0) {
            throw alreadyHandled();
        }
        users.writeAudit(me.userId(), "HORSE_WITHDRAWAL_REJECT", "HORSE_WITHDRAWAL",
                withdrawalId, "SUCCESS", ip);

        HorseResponse horse = horseOf(row);
        notifications.notifyUser(horse.ownerId(), new Notice(
                "HORSE_WITHDRAWAL_REJECTED",
                "Horse withdrawal declined",
                horse.horseName() + ": " + note,
                Notice.HORSE, horse.horseId()));
        return getOrThrow(withdrawalId);
    }

    /* ------------------------------------------------------------------ *
     * helper
     * ------------------------------------------------------------------ */

    /**
     * Một thông báo cho mỗi Groom, gộp mọi buổi bị huỷ của họ. Không trỏ tới
     * buổi tập nào: buổi đã huỷ của ngựa đã rút không còn hiện trên lịch Groom.
     */
    private void notifyGrooms(String horseName, List<TrainingSessionRepository.ScheduledSlot> slots) {
        Map<Integer, List<TrainingSessionRepository.ScheduledSlot>> byGroom = new LinkedHashMap<>();
        for (TrainingSessionRepository.ScheduledSlot s : slots) {
            byGroom.computeIfAbsent(s.assignedTo(), k -> new java.util.ArrayList<>()).add(s);
        }
        byGroom.forEach((groomId, list) -> {
            String first = list.get(0).sessionDate().format(WHEN);
            String message = list.size() == 1
                    ? horseName + " left the club. Your session on " + first + " was cancelled."
                    : horseName + " left the club. Your " + list.size()
                            + " upcoming sessions (from " + first + ") were cancelled.";
            notifications.notifyUser(groomId, new Notice(
                    "TRAINING_SESSION_CANCELLED",
                    "Training session cancelled",
                    message, null, null));
        });
    }

    private WithdrawalRow getOrThrow(int withdrawalId) {
        return withdrawals.findById(withdrawalId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Withdrawal request #" + withdrawalId + " does not exist"));
    }

    private HorseResponse horseOf(WithdrawalRow row) {
        return horses.findById(row.horseId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Horse #" + row.horseId() + " does not exist"));
    }

    private void requirePending(WithdrawalRow row) {
        if (!"PENDING".equals(row.status())) {
            throw alreadyHandled();
        }
    }

    private ApiException alreadyHandled() {
        return new ApiException(HttpStatus.CONFLICT,
                "This request has already been handled");
    }

    private static String plural(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
