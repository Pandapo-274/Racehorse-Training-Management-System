package racehorse.backend.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import racehorse.backend.dto.HorseResponse;
import racehorse.backend.dto.WithdrawalByClubRequest;
import racehorse.backend.dto.WithdrawalReviewRequest;
import racehorse.backend.dto.WithdrawalSubmitRequest;
import racehorse.backend.entity.ContractRow;
import racehorse.backend.entity.Notice;
import racehorse.backend.entity.WithdrawalRow;
import racehorse.backend.exception.ApiException;
import racehorse.backend.repository.ContractRepository;
import racehorse.backend.repository.ExpenseRepository;
import racehorse.backend.repository.HorseRepository;
import racehorse.backend.repository.TrainingPlanRepository;
import racehorse.backend.repository.TrainingSessionRepository;
import racehorse.backend.repository.UserRepository;
import racehorse.backend.repository.WithdrawalRepository;
import racehorse.backend.security.AuthenticatedUser;

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
 *
 * V62 (hợp đồng V49, điều 9): chủ chọn ngày đón ngựa; manager duyệt từ ngày đó trở đi (trước đó ngựa
 * vẫn tập). Hợp đồng được trả tới max(ngày đón, ngày gửi + 15); tháng bắt đầu sau đó được hoàn tiền.
 * Duyệt cũng huỷ bản gia hạn đang dở, hoàn phí giải còn trong hạn, và ghi số tiền CLB nợ chủ.
 * Manager xem "cơ sở" trước khi duyệt: hợp đồng, tiền hoàn, chi phí chưa trả, khoá tập, giải sắp đua.
 * CLB tự rút được ngựa đã hết hạn mà chủ không gia hạn (byClub).
 */
@Service
public class WithdrawalService {

    // Định dạng ngày giờ trong thông báo cho Groom, vd. "17 Nov 2026 07:30".
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH);
    private static final java.time.ZoneId CLUB_ZONE = java.time.ZoneId.of("Asia/Ho_Chi_Minh");

    private final WithdrawalRepository withdrawals;
    private final HorseRepository horses;
    private final TrainingSessionRepository sessions;
    private final TrainingPlanRepository plans;
    private final UserRepository users;
    private final NotificationService notifications;
    private final ContractRepository contracts;
    private final ExpenseRepository expenses;

    public WithdrawalService(WithdrawalRepository withdrawals, HorseRepository horses,
                             TrainingSessionRepository sessions, TrainingPlanRepository plans,
                             UserRepository users, NotificationService notifications,
                             ContractRepository contracts, ExpenseRepository expenses) {
        this.withdrawals = withdrawals;
        this.horses = horses;
        this.sessions = sessions;
        this.plans = plans;
        this.users = users;
        this.notifications = notifications;
        this.contracts = contracts;
        this.expenses = expenses;
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
        String leave = HorsePolicy.leaveOnProblem(raw.leaveOn(), today());
        if (leave != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, leave, Map.of("leaveOn", leave));
        }

        int id;
        try {
            id = withdrawals.insert(horse.horseId(), me.userId(), raw.reason().trim(), raw.leaveOn(), false);
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
                saved.requesterName() + " asked to withdraw " + saved.horseName() + " from the club, collecting it on "
                        + raw.leaveOn() + ".",
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
        // V62: the horse keeps training until the day the owner collects it.
        if (row.leaveOn() != null && today().isBefore(row.leaveOn())) {
            throw new ApiException(HttpStatus.CONFLICT, horse.horseName() + " leaves on " + row.leaveOn()
                    + ". Approve on or after that day; until then it keeps training.");
        }
        String note = review == null ? null : blankToNull(review.note());
        return withdraw(row, horse, note, me, ip);
    }

    /**
     * V62: the club withdraws a horse itself - only one whose stay ended without renewal, so the
     * "Ended - follow up" list has a way out when the owner does not answer.
     */
    @Transactional
    public WithdrawalRow withdrawByClub(WithdrawalByClubRequest raw, AuthenticatedUser me, String ip) {
        HorseResponse horse = horses.findById(raw.horseId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Horse #" + raw.horseId() + " does not exist"));
        if (horse.isWithdrawn()) {
            throw new ApiException(HttpStatus.CONFLICT, horse.horseName() + " has already left the club");
        }
        if (!horses.hasExpiredContract(horse.horseId())) {
            throw new ApiException(HttpStatus.CONFLICT, "The club withdraws a horse itself only when its stay ended "
                    + "without renewal. Otherwise the owner asks for it");
        }
        if (withdrawals.hasOpenRequest(horse.horseId())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    horse.horseName() + "'s owner has already asked to withdraw it: approve that request");
        }
        String reason = raw.reason().trim();
        int id;
        try {
            id = withdrawals.insert(horse.horseId(), me.userId(), reason, today(), true);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "A withdrawal request for this horse is already waiting for the club");
        }
        users.writeAudit(me.userId(), "HORSE_WITHDRAWAL_BY_CLUB", "HORSE_WITHDRAWAL", id, "SUCCESS", ip,
                "Reason: " + reason);
        return withdraw(getOrThrow(id), horse, reason, me, ip);
    }

    /** Everything a withdrawal does, in the caller's transaction. */
    private WithdrawalRow withdraw(WithdrawalRow row, HorseResponse horse, String note,
                                   AuthenticatedUser me, String ip) {
        // Toàn bộ trong một giao dịch: nếu bước nào ném lỗi thì mọi thay đổi trước đó (huỷ
        // buổi tập, kết thúc giáo án, xoá việc chăm sóc, rút khỏi giải) đều được hoàn tác.
        int withdrawalId = row.withdrawalId();
        int horseId = horse.horseId();

        /* Buổi tập đang chạy thì không cắt ngang: Groom đang dắt ngựa trên
           đường đua và còn phải nhập số đo. Đợi buổi đó kết thúc rồi duyệt. */
        if (sessions.hasRunningSession(horseId)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    horse.horseName() + " is in a training session right now. "
                            + "Approve after the session ends.");
        }

        // V62: money first, read before the contract is closed: refund under clause 9, race fees.
        Basis basis = basis(horse, row.leaveOn(), requestedOn(row), today());
        String fees = settleRaceFees(horse, me, ip);

        // Đọc trước khi huỷ để biết báo cho Groom nào, về buổi nào.
        List<TrainingSessionRepository.ScheduledSlot> slots = sessions.findScheduledByHorse(horseId);
        int cancelled = sessions.cancelScheduledByHorse(horseId);
        int ended = plans.completeOpenPlansByHorse(horseId);

        /* Việc chăm sóc chưa làm và các giải chưa đua cũng phải dừng: không thì
           lịch của Groom vẫn hiện việc cho một con ngựa đã đi, và con ngựa vẫn
           có tên trong danh sách thi đấu. */
        LocalDate today = today();
        List<WithdrawalRepository.PendingTask> tasks = withdrawals.findPendingTasks(horseId, today);
        int removedTasks = withdrawals.deletePendingTasks(horseId, today);
        int scratched = withdrawals.scratchFutureRaces(horseId, today, "horse withdrawn from the club");

        String releasedStall = horse.stallNo();
        if (horses.markWithdrawn(horseId) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, horse.horseName() + " has already left the club");
        }

        String refundLine = basis.refundAmount() > 0
                ? "The club refunds " + vnd(basis.refundAmount()) + " for " + plural(basis.refundMonths(), "month")
                        + " not started (stay paid until " + basis.noticeUntil() + ", contract clause 9)."
                : "";
        if (basis.contractId() != null) {
            contracts.setClosedNote(basis.contractId(), "Horse withdrawn from the club on " + today()
                    + (basis.refundAmount() > 0 ? ". Refund owed: " + vnd(basis.refundAmount()) + " for "
                            + plural(basis.refundMonths(), "month") + " not started" : ""));
        }
        if (withdrawals.markApproved(withdrawalId, me.userId(), note,
                releasedStall, cancelled, ended, basis.noticeUntil(), basis.refundMonths(),
                basis.refundAmount(), basis.contractId()) == 0) {
            // @Transactional cuốn mọi thứ ở trên đi cùng.
            throw alreadyHandled();
        }

        List<String> done = new ArrayList<>();
        if (releasedStall != null) done.add("Stall " + releasedStall + " released");
        if (basis.contractId() != null) done.add("Contract #" + basis.contractId() + " ended");
        if (basis.refundAmount() > 0) done.add("Refund owed " + vnd(basis.refundAmount()) + " for "
                + plural(basis.refundMonths(), "month") + " not started (paid until " + basis.noticeUntil() + ")");
        users.writeAudit(me.userId(), "HORSE_WITHDRAWAL_APPROVE", "HORSE_WITHDRAWAL",
                withdrawalId, "SUCCESS", ip, done.isEmpty() ? null : String.join(". ", done));
        users.writeAudit(me.userId(), "HORSE_WITHDRAW", "HORSE", horseId, "SUCCESS", ip);
        if (cancelled > 0) {
            users.writeAudit(me.userId(), "TRAINING_SESSION_CANCEL_BULK", "HORSE", horseId, "SUCCESS", ip);
        }
        if (ended > 0) {
            users.writeAudit(me.userId(), "TRAINING_PLAN_COMPLETE_BULK", "HORSE", horseId, "SUCCESS", ip);
        }
        if (removedTasks > 0) {
            users.writeAudit(me.userId(), "DAILY_TASK_REMOVE_BULK", "HORSE", horseId, "SUCCESS", ip);
        }
        if (scratched > 0) {
            users.writeAudit(me.userId(), "RACE_SCRATCH_BULK", "HORSE", horseId, "SUCCESS", ip);
        }

        notifyGrooms(horse.horseName(), slots, tasks);

        String races = scratched == 0 ? ""
                : " " + (scratched == 1 ? "1 race entry was" : scratched + " race entries were") + " scratched."
                        + fees;

        notifications.notifyUser(horse.ownerId(), new Notice(
                "HORSE_WITHDRAWAL_APPROVED",
                row.byClub() ? "Horse withdrawn by the club" : "Horse withdrawal approved",
                (row.byClub()
                        ? horse.horseName() + "'s stay ended without renewal, so the club has withdrawn it. Reason: "
                                + row.reason() + "."
                        : horse.horseName() + " has been withdrawn from the club.")
                        + races + (refundLine.isEmpty() ? "" : " " + refundLine)
                        + (note == null || row.byClub() ? "" : " " + note),
                Notice.HORSE, horseId));

        notifications.notifyHorseTrainer(horseId, me, new Notice(
                "HORSE_WITHDRAWN",
                "Horse withdrawn from the club",
                horse.horseName() + " has left the club. "
                        + plural(ended, "training plan") + " ended, "
                        + plural(cancelled, "scheduled session") + " cancelled." + races,
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
                withdrawalId, "SUCCESS", ip, "Reason: " + note);

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
     * Một thông báo cho mỗi Groom, gộp mọi buổi tập và việc chăm sóc bị huỷ của
     * họ. Không trỏ tới buổi tập nào: việc của ngựa đã rút không còn hiện trên
     * lịch Groom.
     */
    private void notifyGrooms(String horseName, List<TrainingSessionRepository.ScheduledSlot> slots,
                              List<WithdrawalRepository.PendingTask> tasks) {
        // Gom theo Groom: mỗi Groom nhận MỘT thông báo dù có nhiều buổi/việc bị huỷ.
        // computeIfAbsent: tạo danh sách rỗng nếu chưa có key; merge(..., 1, Integer::sum): đếm +1.
        Map<Integer, List<TrainingSessionRepository.ScheduledSlot>> sessionsByGroom = new LinkedHashMap<>();
        for (TrainingSessionRepository.ScheduledSlot s : slots) {
            sessionsByGroom.computeIfAbsent(s.assignedTo(), k -> new ArrayList<>()).add(s);
        }
        Map<Integer, Integer> tasksByGroom = new LinkedHashMap<>();
        for (WithdrawalRepository.PendingTask t : tasks) {
            tasksByGroom.merge(t.assignedTo(), 1, Integer::sum);
        }

        Set<Integer> grooms = new LinkedHashSet<>(sessionsByGroom.keySet());
        grooms.addAll(tasksByGroom.keySet());
        for (int groomId : grooms) {
            List<TrainingSessionRepository.ScheduledSlot> list = sessionsByGroom.getOrDefault(groomId, List.of());
            int taskCount = tasksByGroom.getOrDefault(groomId, 0);

            List<String> parts = new ArrayList<>();
            if (list.size() == 1) {
                parts.add("your session on " + list.get(0).sessionDate().format(WHEN));
            } else if (list.size() > 1) {
                parts.add("your " + list.size() + " upcoming sessions (from "
                        + list.get(0).sessionDate().format(WHEN) + ")");
            }
            if (taskCount > 0) {
                parts.add(plural(taskCount, "stable task"));
            }
            notifications.notifyUser(groomId, new Notice(
                    list.isEmpty() ? "DAILY_TASK_CANCELLED" : "TRAINING_SESSION_CANCELLED",
                    list.isEmpty() ? "Stable tasks cancelled" : "Training session cancelled",
                    horseName + " left the club. Cancelled: " + String.join(" and ", parts) + ".",
                    null, null));
        }
    }

    /* ------------------------------------------------------------------ *
     * V62: the basis the manager decides on, and the owner sees before asking
     * ------------------------------------------------------------------ */

    public record Basis(int horseId, String horseName,
                        String stay, Integer contractId, LocalDate stayStart, LocalDate stayEnd, Long daysLeft,
                        int months, long paid,
                        LocalDate requestedOn, LocalDate leaveOn, LocalDate noticeUntil,
                        int refundMonths, long refundAmount, boolean renewalOpen,
                        int unpaidCosts, long unpaidAmount, int disputedCosts,
                        String activeLock,
                        int upcomingRaces, int feesRefunded, int feesRefundedByClub, int feesKept,
                        int scheduledSessions, int openPlans, boolean inSessionNow) {}

    /** Before asking: what leaving on leaveOn would mean (owner of the horse, or the manager). */
    public Basis preview(int horseId, LocalDate leaveOn, AuthenticatedUser me) {
        HorseResponse horse = horses.findById(horseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Horse #" + horseId + " does not exist"));
        if ("HORSE_OWNER".equals(me.role()) && horse.ownerId() != me.userId()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only withdraw your own horses");
        }
        return basis(horse, leaveOn, today(), today());
    }

    /** For a request already sent: what approving it today would do. */
    public Basis basisOf(int withdrawalId, AuthenticatedUser me) {
        WithdrawalRow row = getOrThrow(withdrawalId);
        HorseResponse horse = horseOf(row);
        if ("HORSE_OWNER".equals(me.role()) && horse.ownerId() != me.userId()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This request is about another owner's horse");
        }
        return basis(horse, row.leaveOn(), requestedOn(row), today());
    }

    Basis basis(HorseResponse horse, LocalDate leaveOn, LocalDate requestedOn, LocalDate today) {
        int horseId = horse.horseId();
        ContractRow running = contracts.findRunning(horseId).orElse(null);
        boolean takeover = running == null && contracts.findWaitingTakeover(horseId).isPresent();
        LocalDate noticeUntil = HorsePolicy.noticeUntil(requestedOn, leaveOn);
        HorsePolicy.Refund refund = running != null && "ACTIVE".equals(running.status())
                ? HorsePolicy.refundFor(running.startDate(), running.months(), running.totalAmount(), noticeUntil)
                : new HorsePolicy.Refund(noticeUntil, 0, 0);

        int refunded = 0, byClub = 0, kept = 0;
        List<WithdrawalRepository.UpcomingEntry> entries = withdrawals.findUpcomingEntries(horseId, today);
        for (WithdrawalRepository.UpcomingEntry e : entries) {
            if (e.feeExpenseId() == null) continue;
            if (!feeRefundable(e, today)) kept++;
            else if (e.feePaid()) byClub++;
            else refunded++;
        }
        WithdrawalRepository.Costs costs = withdrawals.unpaidCosts(horseId);
        WithdrawalRepository.Activity activity = withdrawals.activity(horseId);
        return new Basis(horseId, horse.horseName(),
                running != null ? running.status() : takeover ? "TAKEOVER" : "NONE",
                running == null ? null : running.contractId(),
                running == null ? null : running.startDate(), running == null ? null : running.endDate(),
                running == null ? null : running.daysLeft(),
                running == null ? 0 : running.months(), running == null ? 0 : running.totalAmount(),
                requestedOn, leaveOn, noticeUntil, refund.monthsNotStarted(), refund.amount(),
                contracts.findOpenRenewal(horseId).isPresent(),
                costs.unpaidCount(), costs.unpaidAmount(), costs.disputedCount(),
                withdrawals.activeLock(horseId).orElse(null),
                entries.size(), refunded, byClub, kept,
                activity.scheduledSessions(), activity.openPlans(), sessions.hasRunningSession(horseId));
    }

    /** Same rule as withdrawing one entry on the Races page: free until entries close. */
    private static boolean feeRefundable(WithdrawalRepository.UpcomingEntry e, LocalDate today) {
        LocalDate lastFreeDay = e.entryDeadline() != null ? e.entryDeadline() : e.raceDate().minusDays(1);
        return !today.isAfter(lastFreeDay);
    }

    /** Race fees of the entries this withdrawal scratches, settled like RaceService.scratch. */
    private String settleRaceFees(HorseResponse horse, AuthenticatedUser me, String ip) {
        int voided = 0, owed = 0, kept = 0;
        for (WithdrawalRepository.UpcomingEntry e : withdrawals.findUpcomingEntries(horse.horseId(), today())) {
            if (e.feeExpenseId() == null) continue;
            if (!feeRefundable(e, today())) {
                kept++;
                continue;
            }
            if (expenses.cancel(e.feeExpenseId(), me.userId(), "Horse withdrawn before entries closed") == 1) {
                users.writeAudit(me.userId(), "EXPENSE_VOID", "EXPENSE_LOG", e.feeExpenseId(), "SUCCESS", ip,
                        horse.horseName() + ", race entry fee for " + e.raceName() + ". Reason: horse withdrawn");
                if (e.feePaid()) owed++; else voided++;
            }
        }
        List<String> parts = new ArrayList<>();
        if (voided > 0) parts.add(plural(voided, "entry fee") + " cancelled");
        if (owed > 0) parts.add(plural(owed, "entry fee") + " already paid online, refunded by the club");
        if (kept > 0) parts.add(plural(kept, "entry fee") + " kept: entries had closed");
        return parts.isEmpty() ? "" : " " + String.join("; ", parts) + ".";
    }

    private static LocalDate requestedOn(WithdrawalRow row) {
        return row.createdAt() == null ? LocalDate.now(CLUB_ZONE)
                : row.createdAt().atZone(java.time.ZoneOffset.UTC).withZoneSameInstant(CLUB_ZONE).toLocalDate();
    }

    private static LocalDate today() {
        return LocalDate.now(CLUB_ZONE);
    }

    private static String vnd(long amount) {
        return ContractTerms.vnd(amount);
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
