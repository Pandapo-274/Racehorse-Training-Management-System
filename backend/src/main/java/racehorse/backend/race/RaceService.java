package racehorse.backend.race;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import racehorse.backend.auth.ApiException;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.UserRepository;
import racehorse.backend.expense.ExpenseModels;
import racehorse.backend.expense.ExpenseRepository;
import racehorse.backend.horse.HorsePolicy;
import racehorse.backend.horse.HorseRepository;
import racehorse.backend.horse.HorseResponse;
import racehorse.backend.horse.HorseVitalsRepository;
import racehorse.backend.incident.IncidentRepository;
import racehorse.backend.notification.Notice;
import racehorse.backend.notification.NotificationService;
import racehorse.backend.preventive.PreventiveCareService;

/**
 * Flow 5 - races (V22), entries, results, scratching.
 *
 * Điều kiện đăng ký (cũng là điều kiện hiện ở danh sách "candidates"):
 *  - ngựa của câu lạc bộ (ACADEMY), chưa rút;
 *  - sức khoẻ ELIGIBLE, không bị khoá tập, không còn báo cáo sự cố chưa khám;
 *  - tuổi đua 2..10 (HorsePolicy.FOAL_BELOW, RACING_MAX_AGE);
 *  - vaccination not overdue (UC17 data), and at least app.race.min-rest-days
 *    between two races (#3);
 *  - the race is OPEN, has not run, its entry deadline has not passed and it
 *    is not full; the horse has no other race that day.
 * Warnings that do not block: no completed trial run within
 * app.race.trial-window-days, vaccination never recorded.
 *
 * A result can be changed freely for app.race.result.edit-window-days after
 * the race; after that only the Club Manager may change it, with a reason (#5).
 *
 * Tiền thưởng thuộc về chủ ngựa, không phải doanh thu câu lạc bộ. Hệ thống chưa
 * mô hình phí huấn luyện hay hoa hồng.
 *
 * Entry fee (V36): charged to the horse (EXPENSE_LOG, OTHER) when the owner
 * approves the entry; refunded if the club cancels the race or the horse is
 * withdrawn on or before the entry deadline, kept if withdrawn after it.
 */
@Service
public class RaceService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private final RaceRepository races;
    private final HorseRepository horses;
    private final HorseVitalsRepository vitals;
    private final IncidentRepository incidents;
    private final UserRepository users;
    private final NotificationService notifications;
    private final PreventiveCareService preventive;
    private final ExpenseRepository expenses;
    private final int minRestDays;
    private final int trialWindowDays;
    private final int resultEditWindowDays;

    public RaceService(RaceRepository races, HorseRepository horses, HorseVitalsRepository vitals,
                       IncidentRepository incidents, UserRepository users, NotificationService notifications,
                       PreventiveCareService preventive, ExpenseRepository expenses,
                       @Value("${app.race.min-rest-days:14}") int minRestDays,
                       @Value("${app.race.trial-window-days:30}") int trialWindowDays,
                       @Value("${app.race.result.edit-window-days:7}") int resultEditWindowDays) {
        this.races = races;
        this.horses = horses;
        this.vitals = vitals;
        this.incidents = incidents;
        this.users = users;
        this.notifications = notifications;
        this.preventive = preventive;
        this.expenses = expenses;
        this.minRestDays = minRestDays;
        this.trialWindowDays = trialWindowDays;
        this.resultEditWindowDays = resultEditWindowDays;
    }

    /** Chủ ngựa chỉ thấy ngựa của mình. */
    public List<RaceModels.Entry> list(Integer year, Integer horseId, AuthenticatedUser user) {
        Integer ownerId = "HORSE_OWNER".equals(user.role()) ? user.userId() : null;
        return races.find(year, horseId, ownerId);
    }

    /**
     * Chủ ngựa đồng ý cho ngựa chạy.
     *
     * Kiểm lại giải và con ngựa ngay lúc duyệt, không chỉ lúc Head Trainer đề
     * xuất: giữa hai lúc đó giải có thể đã bị huỷ hoặc đã chạy, và con ngựa có
     * thể vừa bị khoá tập hay có báo cáo sự cố. Không kiểm thì một đề xuất cũ
     * vẫn duyệt được thành một suất đua mà con ngựa không được phép chạy.
     */
    @Transactional
    public RaceModels.Entry approveEntry(int id, AuthenticatedUser user, String ip) {
        RaceModels.Entry e = require(id);
        checkCanManageEntry(e, user);
        if (!"PENDING_APPROVAL".equals(e.state())) {
            throw new ApiException(HttpStatus.CONFLICT, "Entry is not awaiting owner approval");
        }
        RaceModels.RaceEvent race = requireRace(e.raceId());
        if (!"OPEN".equals(race.status())) {
            throw new ApiException(HttpStatus.CONFLICT, race.raceName() + " has been cancelled");
        }
        if (!race.raceDate().isAfter(LocalDate.now())) {
            throw new ApiException(HttpStatus.CONFLICT, race.raceName() + " has already been run");
        }
        HorseResponse h = horses.findById(e.horseId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Horse #" + e.horseId() + " not found"));
        Check c = check(h, race);
        if (!c.problems.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This entry can no longer be approved: " + c.problems.get(0));
        }
        if (races.updateApproval(id, "APPROVED", null) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "This entry has just been changed by the head trainer");
        }
        users.writeAudit(user.userId(), "RACE_OWNER_APPROVE", "RACE_REGISTRATION", id, "SUCCESS", ip);
        String fee = chargeEntryFee(e, race, user, ip);
        notifications.notifyRole("HEAD_TRAINER", user, new Notice(
                "RACE_APPROVED", "Race entry approved",
                "Owner " + e.ownerName() + " approved " + e.horseName() + " for " + e.raceName()
                        + (fee == null ? "." : " (entry fee " + fee + " charged)."),
                Notice.HORSE, e.horseId()));
        return require(id);
    }

    /**
     * V36: the owner has just agreed to the fee shown in the proposal, so it
     * becomes a cost of the horse (EXPENSE_LOG, OTHER) on their expense page,
     * where they can also dispute it (V30). The row id is kept on the entry so a
     * scratch can void exactly this charge.
     *
     * @return the fee as text, or null when the race has no fee.
     */
    private String chargeEntryFee(RaceModels.Entry e, RaceModels.RaceEvent race, AuthenticatedUser owner,
                                  String ip) {
        BigDecimal fee = race.entryFee();
        if (fee == null || fee.signum() <= 0) {
            return null;
        }
        String note = clip("Race entry fee: " + race.raceName() + " (" + race.raceDate().format(DAY) + ")", 255);
        int expenseId = expenses.insert(new ExpenseModels.ExpenseRequest(e.horseId(), "OTHER", fee,
                LocalDate.now(), note), e.registeredBy());
        races.setFeeExpense(e.registrationId(), expenseId);
        String amount = fee.stripTrailingZeros().toPlainString();
        users.writeAudit(owner.userId(), "EXPENSE_CREATE", "EXPENSE_LOG", expenseId, "SUCCESS", ip,
                e.horseName() + ", OTHER, " + amount + ": " + note);
        return amount;
    }

    /**
     * V36: what happens to the fee when an approved entry is scratched.
     * Refunded (the expense is voided, V19) when the club cancels the race or
     * the horse is withdrawn on or before the entry deadline; kept after the
     * deadline - entries are closed and the place was held.
     *
     * @return a sentence for the owner's notice, or "" when no fee was charged.
     */
    private String settleEntryFee(RaceModels.Entry e, boolean raceCancelled, AuthenticatedUser user, String ip) {
        Integer expenseId = races.findFeeExpense(e.registrationId()).orElse(null);
        if (expenseId == null) {
            return "";
        }
        RaceModels.RaceEvent race = requireRace(e.raceId());
        LocalDate lastFreeDay = race.entryDeadline() != null ? race.entryDeadline() : race.raceDate().minusDays(1);
        boolean refund = raceCancelled || !LocalDate.now().isAfter(lastFreeDay);
        if (!refund) {
            return " The entry fee is not refunded: entries closed on " + lastFreeDay.format(DAY) + ".";
        }
        String why = raceCancelled ? "Race cancelled by the club" : "Withdrawn before entries closed";
        if (expenses.cancel(expenseId, user.userId(), why) == 1) {
            users.writeAudit(user.userId(), "EXPENSE_VOID", "EXPENSE_LOG", expenseId, "SUCCESS", ip,
                    e.horseName() + ", race entry fee for " + e.raceName() + ". Reason: " + why);
        }
        return " The entry fee has been refunded.";
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    @Transactional
    public RaceModels.Entry rejectEntry(int id, String reason, AuthenticatedUser user, String ip) {
        RaceModels.Entry e = require(id);
        checkCanManageEntry(e, user);
        if (!"PENDING_APPROVAL".equals(e.state())) {
            throw new ApiException(HttpStatus.CONFLICT, "Entry is not awaiting owner approval");
        }
        if (races.updateApproval(id, "REJECTED", reason) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "This entry has just been changed by the head trainer");
        }
        users.writeAudit(user.userId(), "RACE_OWNER_REJECT", "RACE_REGISTRATION", id, "SUCCESS", ip, reason);
        notifications.notifyRole("HEAD_TRAINER", user, new Notice(
                "RACE_REJECTED", "Race entry declined",
                "Owner " + e.ownerName() + " declined " + e.horseName() + " for " + e.raceName()
                        + (reason == null || reason.isBlank() ? "." : ": " + reason.trim()),
                Notice.HORSE, e.horseId()));
        return require(id);
    }

    private void checkCanManageEntry(RaceModels.Entry e, AuthenticatedUser user) {
        if ("HORSE_OWNER".equals(user.role()) && e.ownerId() != user.userId()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This is not your horse");
        }
    }

    public List<Integer> years() {
        return races.years();
    }

    /**
     * Horses that could be entered. With raceId, the checks that depend on the
     * race (rest days between races) are included too.
     */
    public List<RaceModels.Candidate> candidates(Integer raceId) {
        RaceModels.RaceEvent race = raceId == null ? null : requireRace(raceId);
        List<RaceModels.Candidate> out = new ArrayList<>();
        for (HorseResponse h : horses.findActive()) {
            if (!HorsePolicy.ACADEMY.equals(HorsePolicy.normalizeType(h.recordType()))) continue;
            Integer age = h.dateOfBirth() == null ? null : HorsePolicy.ageOf(h.dateOfBirth());
            Check c = check(h, race);
            out.add(new RaceModels.Candidate(h.horseId(), h.horseName(), h.stallNo(), h.ownerName(),
                    age, h.status(), c.problems.isEmpty() ? null : c.problems.get(0), c.problems, c.warnings));
        }
        return out;
    }

    /* ---------------- Races (V22) ---------------- */

    /** upcomingOnly: races from today on (for the entry form); otherwise every race. */
    public List<RaceModels.RaceEvent> races(boolean upcomingOnly) {
        return races.listRaces(upcomingOnly ? LocalDate.now() : null);
    }

    @Transactional
    public RaceModels.RaceEvent createRace(RaceModels.RaceEventRequest r, AuthenticatedUser user, String ip) {
        String name = r.raceName().trim().replaceAll("\\s+", " ");
        if (races.existsByName(name)) {
            throw new ApiException(HttpStatus.CONFLICT, "A race with name '" + name + "' already exists",
                    Map.of("raceName", "A race with this name already exists"));
        }
        LocalDate today = LocalDate.now();
        if (!r.raceDate().isAfter(today)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Race date must be in the future",
                    Map.of("raceDate", "Choose a day after today"));
        }
        if (r.raceDate().isAfter(today.plusYears(1))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Race date is too far ahead",
                    Map.of("raceDate", "At most one year ahead"));
        }
        if (r.entryDeadline() != null && r.entryDeadline().isAfter(r.raceDate())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid entry deadline",
                    Map.of("entryDeadline", "Deadline must be on or before the race day"));
        }
        if (r.entryDeadline() != null && r.entryDeadline().isBefore(today)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid entry deadline",
                    Map.of("entryDeadline", "Deadline cannot be in the past"));
        }
        int id;
        try {
            id = races.insertRace(r, name, user.userId());
        } catch (DuplicateKeyException e) {
            // UQ_RACE; the collation is case-insensitive, so "cup" and "Cup" collide too.
            throw new ApiException(HttpStatus.CONFLICT, name + " on " + r.raceDate().format(DAY) + " already exists");
        }
        RaceModels.RaceEvent race = requireRace(id);
        users.writeAudit(user.userId(), "RACE_CREATE", "RACE", id, "SUCCESS", ip, describe(race));
        return race;
    }

    /**
     * Cancels a race that has not run. Every open entry is scratched through
     * the same path as a single scratch, so each owner is told and each entry
     * gets its own audit line.
     */
    @Transactional
    public RaceModels.RaceEvent cancelRace(int raceId, String reason, AuthenticatedUser user, String ip) {
        RaceModels.RaceEvent race = requireRace(raceId);
        if (!"OPEN".equals(race.status())) {
            throw new ApiException(HttpStatus.CONFLICT, race.raceName() + " is already cancelled");
        }
        if (races.resultsRecorded(raceId) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, race.raceName() + " already has results and cannot be cancelled");
        }
        String why = reason.trim();
        races.lockRace(raceId);
        if (races.cancelRace(raceId, why) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "This race has just been changed by someone else");
        }
        List<RaceModels.Entry> open = races.openEntries(raceId);
        for (RaceModels.Entry e : open) {
            doScratch(e, "Race cancelled: " + why, true, user, ip);
        }
        users.writeAudit(user.userId(), "RACE_CANCEL", "RACE", raceId, "SUCCESS", ip,
                describe(race) + ". " + open.size() + " entr" + (open.size() == 1 ? "y" : "ies")
                        + " scratched. Reason: " + why);
        return requireRace(raceId);
    }

    /* ---------------- Entries ---------------- */

    @Transactional
    public RaceModels.Entry register(RaceModels.RegisterRequest r, AuthenticatedUser user, String ip) {
        HorseResponse h = horses.findById(r.horseId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Horse #" + r.horseId() + " not found"));
        RaceModels.RaceEvent race = requireRace(r.raceId());
        LocalDate today = LocalDate.now();
        if (!"OPEN".equals(race.status())) {
            throw new ApiException(HttpStatus.CONFLICT, race.raceName() + " has been cancelled");
        }
        if (!race.raceDate().isAfter(today)) {
            throw new ApiException(HttpStatus.CONFLICT, race.raceName() + " has already been run");
        }
        if (race.entryDeadline() != null && today.isAfter(race.entryDeadline())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Entries for " + race.raceName() + " closed on " + race.entryDeadline().format(DAY));
        }
        Check c = check(h, race);
        if (!c.problems.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, c.problems.get(0));
        }
        var existing = races.findEntry(h.horseId(), race.raceId());
        /* Một đề xuất bị rút, hoặc bị chủ từ chối, đề xuất lại được (dùng lại dòng
           cũ - reinstate). Trước đây đề xuất bị từ chối vẫn bị coi là "đã đăng ký",
           nên Head Trainer không bao giờ hỏi lại chủ được cho giải đó. */
        boolean reusable = existing.isPresent() && (existing.get().scratched() || existing.get().rejected());
        if (existing.isPresent() && !reusable) {
            throw new ApiException(HttpStatus.CONFLICT, h.horseName() + " is already registered for "
                    + race.raceName() + " on " + race.raceDate().format(DAY));
        }
        races.otherRaceOn(h.horseId(), race.raceDate()).ifPresent(other -> {
            throw new ApiException(HttpStatus.CONFLICT,
                    h.horseName() + " already runs in " + other + " on " + race.raceDate().format(DAY));
        });
        /* Capacity: lock the race row first, so two trainers taking the last
           place queue up and the second one sees the race full. */
        if (race.maxEntries() != null) {
            races.lockRace(race.raceId());
            RaceModels.RaceEvent fresh = requireRace(race.raceId());
            if (fresh.entries() >= fresh.maxEntries()) {
                throw new ApiException(HttpStatus.CONFLICT, race.raceName() + " is full ("
                        + fresh.maxEntries() + " horses)");
            }
        }

        int id;
        if (reusable) {
            /* The horse was scratched from (or declined for) this very race earlier.
               Re-entering it reuses that row - see RaceRepository.reinstate for why. */
            id = existing.get().id();
            if (races.reinstate(id, user.userId(), blankToNull(r.note())) == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "This entry has just been updated by someone else");
            }
            users.writeAudit(user.userId(), "RACE_REINSTATE", "RACE_REGISTRATION", id, "SUCCESS", ip,
                    h.horseName() + " back in " + race.raceName() + " on " + race.raceDate());
        } else {
            try {
                id = races.insert(h.horseId(), race, user.userId(), blankToNull(r.note()));
            } catch (DuplicateKeyException e) {
                throw new ApiException(HttpStatus.CONFLICT, h.horseName() + " has just been registered for "
                        + race.raceName() + " by someone else");
            }
            users.writeAudit(user.userId(), "RACE_REGISTER", "RACE_REGISTRATION", id, "SUCCESS", ip,
                    h.horseName() + " in " + race.raceName() + " on " + race.raceDate()
                            + (c.warnings.isEmpty() ? "" : ". Warnings: " + String.join("; ", c.warnings)));
        }
        notifications.notifyUser(h.ownerId(), new Notice(
                "RACE_REGISTERED", "Race entry proposal",
                h.horseName() + " has been proposed to enter " + race.raceName() + " on " + race.raceDate().format(DAY)
                        + (race.entryFee() == null || race.entryFee().signum() == 0 ? ""
                                : " (entry fee " + race.entryFee().stripTrailingZeros().toPlainString() + ")")
                        + ". Please review and approve.",
                Notice.HORSE, h.horseId()));
        return require(id);
    }

    /**
     * Kết quả chỉ nhập được từ ngày đua trở đi; nhập lại được để sửa sai (có audit).
     *
     * #5: changing a result that already exists is open to Head Trainer and
     * Club Manager for resultEditWindowDays after the race. After that it is
     * locked: only the Club Manager may change it, and must give a reason,
     * which goes into the audit with the old and new values. A first result
     * entered late is not a change and is always allowed.
     */
    @Transactional
    public RaceModels.Entry recordResult(int id, RaceModels.ResultRequest r, AuthenticatedUser user, String ip) {
        RaceModels.Entry e = require(id);
        if ("SCRATCHED".equals(e.result())) {
            throw new ApiException(HttpStatus.CONFLICT, "This entry was scratched and has no result");
        }
        /* Chỉ ngựa chủ đã đồng ý mới được chạy, nên chỉ nó mới có kết quả. Không
           chặn thì đề xuất chưa duyệt hay bị từ chối vẫn ghi được WON kèm tiền
           thưởng, và tổng thưởng của câu lạc bộ đếm cả nó. */
        if (!"APPROVED".equals(e.approvalStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "PENDING_OWNER".equals(e.approvalStatus())
                    ? "The owner never approved this entry, so the horse did not run"
                    : "The owner declined this entry, so the horse did not run");
        }
        if (e.raceDate().isAfter(LocalDate.now())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "The race is on " + e.raceDate().format(DAY) + ". Record the result after it has run");
        }
        boolean change = e.result() != null;
        boolean locked = change && LocalDate.now().isAfter(e.raceDate().plusDays(resultEditWindowDays));
        String why = blankToNull(r.reason());
        if (locked && !"CLUB_MANAGER".equals(user.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "The result was locked " + resultEditWindowDays
                    + " days after the race. Ask the club manager to change it");
        }
        if (locked && why == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A reason is required to change a locked result",
                    Map.of("reason", "Say why the result is being changed"));
        }
        BigDecimal prize = r.prizeMoney() == null ? BigDecimal.ZERO : r.prizeMoney();
        if ("UNPLACED".equals(r.result()) && prize.signum() != 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "An unplaced horse earns no prize money",
                    Map.of("prizeMoney", "Set prize money to 0 for UNPLACED"));
        }
        /* PLACED = finished 2nd or 3rd. Written into the spec, and checked here
           whenever a position is given, so "PLACED" cannot mean 7th. */
        Integer pos = r.finishPosition();
        if (pos != null) {
            String expected = pos == 1 ? "WON" : pos <= 3 ? "PLACED" : "UNPLACED";
            if (!expected.equals(r.result())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Position " + pos + " is " + expected
                        + ", not " + r.result(),
                        Map.of("finishPosition", "WON = 1st, PLACED = 2nd-3rd, UNPLACED = 4th or later"));
            }
        }
        String note = r.note() == null ? e.note() : blankToNull(r.note());
        if (races.saveResult(id, r.result(), prize, note, pos, r.finishTimeMs()) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "This entry has just been scratched");
        }
        /* Re-entering overwrites the row, so the old value survives only here
           (review #5): "WON, 1st, 100000000 -> PLACED, 2nd, 40000000". */
        users.writeAudit(user.userId(), "RACE_RESULT", "RACE_REGISTRATION", id, "SUCCESS", ip,
                e.horseName() + " in " + e.raceName() + " on " + e.raceDate() + ": "
                        + resultText(e.result(), e.finishPosition(), e.prizeMoney()) + " -> "
                        + resultText(r.result(), pos, prize)
                        + (locked ? " (after lock). Reason: " + why : ""));
        notifications.notifyUser(e.ownerId(), new Notice(
                "RACE_RESULT", change ? "Race result changed" : "Race result",
                e.horseName() + " in " + e.raceName() + ": " + r.result().toLowerCase(Locale.ROOT)
                        + (pos == null ? "" : " (" + ordinal(pos) + ")")
                        + (prize.signum() > 0 ? ", prize " + prize.stripTrailingZeros().toPlainString() : "") + ".",
                Notice.HORSE, e.horseId()));
        return require(id);
    }

    /** Rút khỏi giải trước ngày đua (ngựa ốm, bị khoá tập...). */
    @Transactional
    public RaceModels.Entry scratch(int id, String reason, AuthenticatedUser user, String ip) {
        RaceModels.Entry e = require(id);
        if (e.result() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "Only an entry without a result can be scratched");
        }
        if ("REJECTED".equals(e.approvalStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "The owner already declined this entry; there is nothing to withdraw");
        }
        if (e.raceDate().isBefore(LocalDate.now())) {
            throw new ApiException(HttpStatus.CONFLICT, "The race has already run. Record its result instead");
        }
        doScratch(e, reason.trim(), false, user, ip);
        return require(id);
    }

    /**
     * V23: the reason has its own column; the entry's note is left untouched.
     * V36: an entry fee already charged is refunded or kept (settleEntryFee),
     * and the owner is told which.
     */
    private void doScratch(RaceModels.Entry e, String reason, boolean raceCancelled, AuthenticatedUser user,
                           String ip) {
        String why = reason.length() <= 500 ? reason : reason.substring(0, 500);
        if (races.scratch(e.registrationId(), why) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "This entry has just been updated by someone else");
        }
        users.writeAudit(user.userId(), "RACE_SCRATCH", "RACE_REGISTRATION", e.registrationId(), "SUCCESS", ip,
                e.horseName() + " from " + e.raceName() + " on " + e.raceDate() + ". Reason: " + why);
        String fee = settleEntryFee(e, raceCancelled, user, ip);
        notifications.notifyUser(e.ownerId(), new Notice(
                "RACE_SCRATCHED", "Race entry withdrawn",
                clip(e.horseName() + " was withdrawn from " + e.raceName() + ": " + why + "." + fee, 500),
                Notice.HORSE, e.horseId()));
    }

    private static String ordinal(int n) {
        String suffix = (n % 100 >= 11 && n % 100 <= 13) ? "th"
                : switch (n % 10) { case 1 -> "st"; case 2 -> "nd"; case 3 -> "rd"; default -> "th"; };
        return n + suffix;
    }

    private static String resultText(String result, Integer position, BigDecimal prize) {
        if (result == null) return "no result";
        return result + (position == null ? "" : ", " + ordinal(position)) + ", "
                + (prize == null ? "0" : prize.stripTrailingZeros().toPlainString());
    }

    private static String describe(RaceModels.RaceEvent r) {
        return r.raceName() + " on " + r.raceDate()
                + (r.distanceM() == null ? "" : ", " + r.distanceM() + " m")
                + (r.venue() == null ? "" : ", " + r.venue())
                + (r.entryDeadline() == null ? "" : ", entries close " + r.entryDeadline())
                + (r.maxEntries() == null ? "" : ", max " + r.maxEntries())
                + (r.entryFee() == null ? "" : ", fee " + r.entryFee().stripTrailingZeros().toPlainString());
    }

    public RaceModels.Summary summary(int year) {
        List<RaceModels.Entry> all = races.find(year, null, null);
        Map<Integer, int[]> counts = new LinkedHashMap<>();       // entries, wins, places
        Map<Integer, BigDecimal> prizes = new LinkedHashMap<>();
        Map<Integer, RaceModels.Entry> first = new LinkedHashMap<>();
        int finished = 0, wins = 0, places = 0, unplaced = 0, scratched = 0, upcoming = 0,
            awaitingResult = 0;
        BigDecimal total = BigDecimal.ZERO;

        for (RaceModels.Entry e : all) {
            BigDecimal prize = e.prizeMoney() == null ? BigDecimal.ZERO : e.prizeMoney();
            switch (e.state()) {
                case "SCRATCHED" -> scratched++;
                case "FINISHED" -> finished++;
                // A past race with no result yet is not "upcoming" - see RaceModels.Summary.
                case "AWAITING_RESULT" -> awaitingResult++;
                case "UPCOMING" -> upcoming++;
                default -> {}
            }
            if ("WON".equals(e.result())) wins++;
            if ("PLACED".equals(e.result())) places++;
            if ("UNPLACED".equals(e.result())) unplaced++;
            total = total.add(prize);
            if ("SCRATCHED".equals(e.state()) || "PENDING_APPROVAL".equals(e.state()) || "REJECTED".equals(e.state())) continue;

            int[] c = counts.computeIfAbsent(e.horseId(), k -> new int[3]);
            first.putIfAbsent(e.horseId(), e);
            c[0]++;
            if ("WON".equals(e.result())) c[1]++;
            if ("PLACED".equals(e.result())) c[2]++;
            prizes.merge(e.horseId(), prize, BigDecimal::add);
        }

        List<RaceModels.HorseSummary> perHorse = new ArrayList<>();
        counts.forEach((horseId, c) -> {
            RaceModels.Entry e = first.get(horseId);
            perHorse.add(new RaceModels.HorseSummary(horseId, e.horseName(), e.ownerName(),
                    c[0], c[1], c[2], prizes.get(horseId)));
        });
        perHorse.sort((a, b) -> {
            int byPrize = b.prizeMoney().compareTo(a.prizeMoney());
            return byPrize != 0 ? byPrize : Integer.compare(b.wins(), a.wins());
        });
        return new RaceModels.Summary(year, all.size() - scratched, finished, wins, places, unplaced,
                scratched, upcoming, awaitingResult, total, perHorse);
    }

    /* ---------------- helper ---------------- */

    /** Blocking problems (first one is shown) and warnings that do not block. */
    private record Check(List<String> problems, List<String> warnings) {
    }

    /**
     * Everything that decides whether a horse may race. race may be null (the
     * candidate list before a race is chosen): then the race-dependent rule -
     * rest days between races - is skipped.
     *
     * Every failing rule is collected, not just the first, so the screen can
     * list them all.
     */
    private Check check(HorseResponse h, RaceModels.RaceEvent race) {
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (!HorsePolicy.ACADEMY.equals(HorsePolicy.normalizeType(h.recordType()))) {
            problems.add(h.horseName() + " is a pedigree-only record, not a club horse");
            return new Check(problems, warnings);
        }
        if (h.isWithdrawn()) {
            problems.add(h.horseName() + " has been withdrawn from the club");
            return new Check(problems, warnings);
        }
        if (h.dateOfBirth() == null) {
            problems.add(h.horseName() + " has no date of birth on record");
        } else {
            int age = HorsePolicy.ageOf(h.dateOfBirth());
            if (age < HorsePolicy.FOAL_BELOW || age > HorsePolicy.RACING_MAX_AGE) {
                problems.add(h.horseName() + " is " + age + " years old; racing age is "
                        + HorsePolicy.FOAL_BELOW + " to " + HorsePolicy.RACING_MAX_AGE);
            }
        }
        if (!"ELIGIBLE".equals(h.status())) {
            problems.add(h.horseName() + " is not cleared to race (health status " + h.status() + ")");
        }
        if (vitals.findActiveLock(h.horseId()).isPresent()) {
            problems.add(h.horseName() + " has an active training lock");
        }
        if (incidents.findOpenReport(h.horseId()).isPresent()) {
            problems.add(h.horseName() + " has an incident report waiting for a veterinarian");
        }
        /* #3: vaccination. OVERDUE blocks. NEVER only warns: a horse with no
           record may have been vaccinated before it came to the club, and
           blocking it would stop racing until the history is typed in. */
        preventive.statusOf(h.horseId(), "VACCINATION").ifPresent(v -> {
            if ("OVERDUE".equals(v.careStatus())) {
                problems.add(h.horseName() + "'s vaccination is overdue (was due " + v.nextDueDate() + ")");
            } else if ("NEVER".equals(v.careStatus())) {
                warnings.add("No vaccination on record for " + h.horseName());
            }
        });
        /* #3: rest between races. */
        if (race != null) {
            races.nearbyEntry(h.horseId(), race.raceDate(), minRestDays, race.raceId()).ifPresent(other ->
                    problems.add(h.horseName() + " runs in " + other.raceName() + " on "
                            + other.raceDate().format(DAY) + "; races must be at least " + minRestDays
                            + " days apart"));
        }
        /* #3: trial run. A warning, not a rule - it is the trainer's call. */
        if (!races.hasRecentTrial(h.horseId(), trialWindowDays)) {
            warnings.add("No completed trial run in the last " + trialWindowDays + " days");
        }
        return new Check(problems, warnings);
    }

    private RaceModels.RaceEvent requireRace(int raceId) {
        return races.findRace(raceId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Race #" + raceId + " not found"));
    }

    private RaceModels.Entry require(int id) {
        return races.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Race entry #" + id + " not found"));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

}
