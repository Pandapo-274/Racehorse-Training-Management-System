package racehorse.backend.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import racehorse.backend.dto.HorseResponse;
import racehorse.backend.entity.ExpenseModels;
import racehorse.backend.entity.Notice;
import racehorse.backend.entity.RaceModels;
import racehorse.backend.exception.ApiException;
import racehorse.backend.repository.ExpenseRepository;
import racehorse.backend.repository.HorseRepository;
import racehorse.backend.repository.HorseVitalsRepository;
import racehorse.backend.repository.IncidentRepository;
import racehorse.backend.repository.RaceRepository;
import racehorse.backend.repository.UserRepository;
import racehorse.backend.security.AuthenticatedUser;

/**
 * Flow 5 - races (V22), entries, results, scratching.
 *
 * Điều kiện đăng ký (cũng là điều kiện hiện ở danh sách "candidates"):
 *  - ngựa của câu lạc bộ (ACADEMY), chưa rút;
 *  - sức khoẻ ELIGIBLE, không bị khoá tập, không còn báo cáo sự cố chưa khám;
 *  - tuổi đua 2..10 (HorsePolicy.FOAL_BELOW, RACING_MAX_AGE);
 *  - có hồ sơ tiêm phòng và chưa quá hạn (UC17), có ít nhất một buổi trial run
 *    hoàn thành trong app.race.trial-window-days, và cách giải khác ít nhất
 *    app.race.min-rest-days (#3). Hai luật đầu trước đây chỉ là cảnh báo; nhóm
 *    chốt (11/2026) là chặn, cho khớp với màn Enter a horse;
 *  - the race is OPEN, has not run, its entry deadline has not passed and it
 *    is not full; the horse has no other race that day.
 *
 * Góp ý hội đồng: CHỦ NGỰA là người đăng ký giải (ownerRegister), Head Trainer là người
 * tư vấn: đề xuất một giải kèm ghi chú (register), chủ bấm đăng ký mới thành suất đua.
 * Rút ngựa cũng vậy (V55): chỉ chủ rút suất đã xác nhận; Head Trainer chỉ rút lại đề xuất
 * chủ chưa quyết, còn lại thì gửi lời khuyên rút ngựa (recommendWithdrawal).
 * Head Trainer chỉ đề xuất và tư vấn cho ngựa mình phụ trách (HORSE.trainer_id) hoặc
 * ngựa chưa giao cho ai. Nhập kết quả thì Head Trainer nào cũng được.
 *
 * V59 (phương án A): giải do một đơn vị tổ chức (JRA...) hoặc chính CLB tổ chức. Chỉ Club Manager
 * tạo giải. Chủ xác nhận suất thì CLB thay chủ nộp đơn cho đơn vị tổ chức (ngoài hệ thống) và Club
 * Manager đánh dấu "đã nộp" (markSubmitted). Chỉ suất đã nộp mới nhập được kết quả.
 *
 * Đề xuất chủ chưa duyệt khi hết hạn đăng ký thành EXPIRED (RaceModels.proposalExpired).
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
/*
 * Vòng đời một suất đăng ký (state tính trong RaceRepository.stateOf):
 *
 *   HLV đề xuất (POST /api/races) --> PENDING_APPROVAL (chờ chủ ngựa)
 *       chủ duyệt  --> UPCOMING (trước ngày đua) --qua ngày đua--> AWAITING_RESULT --nhập kết quả--> FINISHED
 *       chủ từ chối (lý do tuỳ chọn) --> REJECTED (HLV có thể đề xuất lại: reinstate dùng lại dòng cũ)
 *       chủ chưa duyệt khi hết hạn đăng ký --> EXPIRED (không duyệt được nữa)
 *   bất kỳ lúc nào trước khi có kết quả: HLV rút / CLB huỷ giải --> SCRATCHED
 *
 * Mọi bước đổi trạng thái đều là UPDATE có điều kiện (approval_status / result) để hai người
 * bấm cùng lúc không cùng thắng.
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
        if (!"PENDING_APPROVAL".equals(e.state()) && !"EXPIRED".equals(e.state())) {
            throw new ApiException(HttpStatus.CONFLICT, "Entry is not awaiting owner approval");
        }
        RaceModels.RaceEvent race = requireRace(e.raceId());
        LocalDate today = LocalDate.now();
        if (!"OPEN".equals(race.status())) {
            throw new ApiException(HttpStatus.CONFLICT, race.raceName() + " has been cancelled");
        }
        if (!race.raceDate().isAfter(today)) {
            throw new ApiException(HttpStatus.CONFLICT, race.raceName() + " has already been run");
        }
        /* Hết hạn đăng ký thì không duyệt nữa: trước đây chủ vẫn duyệt được sau hạn,
           và lệ phí vừa thu đã không hoàn được ngay (settleEntryFee). */
        if (RaceModels.proposalExpired(race.raceDate(), race.entryDeadline(), today)) {
            throw new ApiException(HttpStatus.CONFLICT, "Entries for " + race.raceName() + " closed on "
                    + race.entryDeadline().format(DAY) + ". This proposal can no longer be approved");
        }
        HorseResponse h = horses.findById(e.horseId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Horse #" + e.horseId() + " not found"));
        // Kiểm lại toàn bộ điều kiện của ngựa ngay lúc chủ duyệt (giữa lúc đề xuất và lúc duyệt ngựa có thể
        // vừa bị khoá tập, bị thương, có báo cáo sự cố).
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
        // V59: the club sends the entry to the organizer on the owner's behalf.
        notifications.notifyRole("CLUB_MANAGER", user, new Notice(
                "RACE_TO_SUBMIT", "Submit a race entry",
                clip(e.horseName() + " (" + e.ownerName() + ") is confirmed for " + e.raceName() + " on "
                        + race.raceDate().format(DAY) + ". Submit the entry to " + organizerOf(race)
                        + (race.entryDeadline() == null ? "" : " by " + race.entryDeadline().format(DAY))
                        + ", then mark it submitted.", 500),
                Notice.HORSE, e.horseId()));
        notifyTrainers(e, h.trainerId(), user, new Notice(
                "RACE_APPROVED", "Race entry approved",
                "Owner " + e.ownerName() + " approved " + e.horseName() + " for " + e.raceName()
                        + (fee == null ? "." : " (entry fee " + fee + " charged)."),
                Notice.HORSE, e.horseId()));
        return require(id);
    }

    /**
     * Báo câu trả lời của chủ cho Head Trainer đã đề xuất (registered_by), và cho
     * Head Trainer đang phụ trách ngựa nếu là người khác. Trước đây chỉ báo người
     * phụ trách, nên ai đề xuất ngựa của người khác thì không bao giờ biết kết quả.
     */
    private void notifyTrainers(RaceModels.Entry e, Integer assignedTrainerId, AuthenticatedUser owner,
                                Notice notice) {
        // Chủ tự đăng ký thì registered_by là chính chủ: không tự báo cho mình.
        if (e.registeredBy() != owner.userId()) {
            notifications.notifyUser(e.registeredBy(), notice);
        }
        if (assignedTrainerId != null && assignedTrainerId.intValue() != e.registeredBy()) {
            notifications.notifyHorseTrainer(e.horseId(), owner, notice);
        }
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
        // V57: a fee already paid online cannot be taken back here; the club refunds it.
        boolean paidOnline = expenses.findById(expenseId).map(x -> x.paidAt() != null).orElse(false);
        if (expenses.cancel(expenseId, user.userId(), why) == 1) {
            users.writeAudit(user.userId(), "EXPENSE_VOID", "EXPENSE_LOG", expenseId, "SUCCESS", ip,
                    e.horseName() + ", race entry fee for " + e.raceName() + ". Reason: " + why);
        }
        return paidOnline
                ? " The entry fee was already paid online: the club refunds it to you."
                : " The entry fee has been refunded.";
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
        String why = blankToNull(reason);
        if (races.updateApproval(id, "REJECTED", why) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "This entry has just been changed by the head trainer");
        }
        users.writeAudit(user.userId(), "RACE_OWNER_REJECT", "RACE_REGISTRATION", id, "SUCCESS", ip, why);
        Integer assignedTrainerId = horses.findById(e.horseId()).map(HorseResponse::trainerId).orElse(null);
        notifyTrainers(e, assignedTrainerId, user, new Notice(
                "RACE_REJECTED", "Race entry declined",
                "Owner " + e.ownerName() + " declined " + e.horseName() + " for " + e.raceName()
                        + (why == null ? "." : ": " + why),
                Notice.HORSE, e.horseId()));
        return require(id);
    }

    private void checkCanManageEntry(RaceModels.Entry e, AuthenticatedUser user) {
        if ("HORSE_OWNER".equals(user.role()) && e.ownerId() != user.userId()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This is not your horse");
        }
    }

    /**
     * Head Trainer chỉ đăng ký / rút ngựa mình phụ trách (HORSE.trainer_id), như ở
     * TrainingPlanService; ngựa chưa giao cho ai thì Head Trainer nào cũng làm được.
     * Vai trò khác không bị giới hạn ở đây (@RequireRole đã lọc).
     */
    private static boolean managedBy(Integer trainerId, AuthenticatedUser user) {
        return !"HEAD_TRAINER".equals(user.role()) || trainerId == null || trainerId.intValue() == user.userId();
    }

    private static ApiException notYourHorse(String horseName) {
        return new ApiException(HttpStatus.FORBIDDEN, horseName + " is assigned to another Head Trainer",
                Map.of("horseId", "Only the horse's own Head Trainer can enter or scratch it"));
    }

    public List<Integer> years() {
        return races.years();
    }

    /**
     * Horses that could be entered. With raceId, the checks that depend on the
     * race (rest days between races) are included too. A Head Trainer only sees
     * the horses they may enter (managedBy).
     */
    public List<RaceModels.Candidate> candidates(Integer raceId, AuthenticatedUser user) {
        // Lấy ngựa ACADEMY còn ở CLB mà người gọi phụ trách (managedBy: HLV chỉ thấy ngựa của mình), kèm danh sách
        // lý do chặn (problems) và cảnh báo (warnings) cho từng con.
        RaceModels.RaceEvent race = raceId == null ? null : requireRace(raceId);
        List<RaceModels.Candidate> out = new ArrayList<>();
        for (HorseResponse h : horses.findActive()) {
            if (!HorsePolicy.ACADEMY.equals(HorsePolicy.normalizeType(h.recordType()))) continue;
            if (!managedBy(h.trainerId(), user)) continue;
            if ("HORSE_OWNER".equals(user.role()) && h.ownerId() != user.userId()) continue;
            Integer age = h.dateOfBirth() == null ? null : HorsePolicy.ageOf(h.dateOfBirth());
            Check c = check(h, race);
            out.add(new RaceModels.Candidate(h.horseId(), h.horseName(), h.stallNo(), h.ownerName(),
                    age, h.status(), c.problems.isEmpty() ? null : c.problems.get(0), c.problems, c.warnings,
                    HorsePolicy.ageInMonths(h.dateOfBirth(), LocalDate.now())));
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
        // Chuẩn hoá tên: bỏ khoảng trắng thừa giữa các từ, để "Tracen  Cup" và "Tracen Cup" là một.
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
        // Khoá dòng RACE trước khi huỷ để không ai đăng ký thêm vào giải đang bị huỷ.
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

    /** Head Trainer tư vấn: tạo một đề xuất. Chỉ khi chủ ngựa đăng ký thì ngựa mới có suất đua. */
    @Transactional
    public RaceModels.Entry register(RaceModels.RegisterRequest r, AuthenticatedUser user, String ip) {
        HorseResponse h = horses.findById(r.horseId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Horse #" + r.horseId() + " not found"));
        if (!managedBy(h.trainerId(), user)) {
            throw notYourHorse(h.horseName());
        }
        RaceModels.RaceEvent race = requireRace(r.raceId());
        int id = createEntry(h, race, r.note(), user, ip);
        notifications.notifyUser(h.ownerId(), new Notice(
                "RACE_REGISTERED", "Trainer recommends a race",
                "Your Head Trainer recommends " + h.horseName() + " for " + race.raceName() + " on "
                        + race.raceDate().format(DAY)
                        + (race.entryFee() == null || race.entryFee().signum() == 0 ? ""
                                : " (entry fee " + race.entryFee().stripTrailingZeros().toPlainString() + ")")
                        + ". Register the horse if you agree.",
                Notice.HORSE, h.horseId()));
        return require(id);
    }

    /**
     * Chủ ngựa đăng ký ngựa của mình. Nếu Head Trainer đã đề xuất đúng giải này thì xác nhận đề
     * xuất đó, không thì tạo suất mới. Cả hai đều đi qua approveEntry, nên điều kiện của ngựa,
     * lệ phí và thông báo cho Head Trainer là một đường duy nhất.
     */
    @Transactional
    public RaceModels.Entry ownerRegister(RaceModels.RegisterRequest r, AuthenticatedUser owner, String ip) {
        HorseResponse h = horses.findById(r.horseId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Horse #" + r.horseId() + " not found"));
        if (h.ownerId() != owner.userId()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This is not your horse");
        }
        RaceModels.RaceEvent race = requireRace(r.raceId());
        var existing = races.findEntry(h.horseId(), race.raceId());
        boolean proposed = existing.isPresent() && existing.get().result() == null
                && "PENDING_OWNER".equals(existing.get().approvalStatus());
        int id = proposed ? existing.get().id() : createEntry(h, race, r.note(), owner, ip);
        return approveEntry(id, owner, ip);
    }

    /** Kiểm mọi điều kiện rồi ghi một suất PENDING_OWNER; trả về id. Không gửi thông báo. */
    private int createEntry(HorseResponse h, RaceModels.RaceEvent race, String note,
                            AuthenticatedUser user, String ip) {
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
        // Thứ tự kiểm khi đăng ký: giải còn OPEN, chưa chạy, chưa quá hạn đăng ký -> ngựa qua check() ->
        // chưa có suất (hoặc suất cũ đã rút / bị từ chối thì dùng lại) -> không chạy giải khác cùng ngày ->
        // giải chưa đầy (khoá dòng RACE để đếm chính xác).
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
            if (races.reinstate(id, h.ownerId(), user.userId(), blankToNull(note)) == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "This entry has just been updated by someone else");
            }
            users.writeAudit(user.userId(), "RACE_REINSTATE", "RACE_REGISTRATION", id, "SUCCESS", ip,
                    h.horseName() + " back in " + race.raceName() + " on " + race.raceDate());
        } else {
            try {
                id = races.insert(h.horseId(), h.ownerId(), race, user.userId(), blankToNull(note));
            } catch (DuplicateKeyException e) {
                throw new ApiException(HttpStatus.CONFLICT, h.horseName() + " has just been registered for "
                        + race.raceName() + " by someone else");
            }
            users.writeAudit(user.userId(), "RACE_REGISTER", "RACE_REGISTRATION", id, "SUCCESS", ip,
                    h.horseName() + " in " + race.raceName() + " on " + race.raceDate()
                            + (c.warnings.isEmpty() ? "" : ". Warnings: " + String.join("; ", c.warnings)));
        }
        return id;
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
        // V59: an entry never sent to the organizer did not run.
        if (e.submittedAt() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "This entry was never submitted to "
                    + (e.organizer() == null ? "the organizer" : e.organizer())
                    + ". If it was, the Club Manager marks it submitted first");
        }
        if (e.raceDate().isAfter(LocalDate.now())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "The race is on " + e.raceDate().format(DAY) + ". Record the result after it has run");
        }
        // change = đã có kết quả trước đó (đây là SỬA). locked = đã quá cửa sổ sửa (mặc định 7 ngày sau ngày đua).
        boolean change = e.result() != null;
        boolean locked = change && LocalDate.now().isAfter(e.raceDate().plusDays(resultEditWindowDays));
        String why = blankToNull(r.reason());
        /* 409 chứ không phải 403: Head Trainer vẫn có quyền nhập kết quả, chỉ là kết quả
           này đã khoá. Frontend coi mọi 403 là "không có quyền vào trang" và chuyển sang
           /access-denied, nên trả 403 ở đây đá Head Trainer ra khỏi màn Races. */
        if (locked && !"CLUB_MANAGER".equals(user.role())) {
            throw new ApiException(HttpStatus.CONFLICT, "The result was locked " + resultEditWindowDays
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
    /**
     * Rút ngựa khỏi giải. Góp ý hội đồng (mục 14): chủ ngựa đăng ký, Head Trainer tư vấn, nên
     * CHỈ CHỦ NGỰA rút một suất đã xác nhận. Head Trainer chỉ được rút lại lời đề xuất của mình
     * khi chủ chưa quyết (PENDING_OWNER); với suất đã xác nhận thì dùng recommendWithdrawal.
     * 409 chứ không 403: frontend chuyển mọi 403 sang /access-denied, còn đây là "không phải
     * việc của bạn ở bước này", màn hình phải ở lại và nói cách làm đúng.
     */
    @Transactional
    public RaceModels.Entry scratch(int id, String reason, AuthenticatedUser user, String ip) {
        RaceModels.Entry e = require(id);
        checkCanManageEntry(e, user);
        if (!managedBy(e.trainerId(), user)) {
            throw notYourHorse(e.horseName());
        }
        if ("HEAD_TRAINER".equals(user.role()) && !"PENDING_OWNER".equals(e.approvalStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Only the owner can withdraw a confirmed entry. Send the owner a withdrawal recommendation instead");
        }
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
        if (e.submittedAt() != null) {
            // V59: the organizer already has this entry; the club withdraws it there too.
            notifications.notifyRole("CLUB_MANAGER", user, new Notice("RACE_SCRATCHED", "Withdraw a submitted entry",
                    clip(e.horseName() + " was withdrawn from " + e.raceName() + ". The entry was already submitted to "
                            + (e.organizer() == null ? "the organizer" : e.organizer())
                            + (e.organizerRef() == null ? "" : " (ref " + e.organizerRef() + ")")
                            + ": withdraw it there as well.", 500),
                    Notice.HORSE, e.horseId()));
        }
        if ("HORSE_OWNER".equals(user.role())) {
            notifyTrainers(e, e.trainerId(), user, new Notice("RACE_SCRATCHED", "Owner withdrew a race entry",
                    clip("Owner " + e.ownerName() + " withdrew " + e.horseName() + " from " + e.raceName()
                            + ": " + reason.trim(), 500),
                    Notice.HORSE, e.horseId()));
        }
        return require(id);
    }

    /**
     * V59: Club Manager đánh dấu đã nộp một suất chủ đã xác nhận cho đơn vị tổ chức giải, kèm mã
     * xác nhận của họ nếu có. Chủ ngựa và Head Trainer được báo.
     */
    @Transactional
    public RaceModels.Entry markSubmitted(int id, String organizerRef, AuthenticatedUser user, String ip) {
        RaceModels.Entry e = require(id);
        if (!"APPROVED".equals(e.approvalStatus()) || e.result() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "Only an entry the owner confirmed, not scratched and "
                    + "without a result, can be submitted");
        }
        if (e.submittedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "This entry was already submitted to "
                    + (e.organizer() == null ? "the organizer" : e.organizer()));
        }
        if (!"OPEN".equals(e.raceStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, e.raceName() + " has been cancelled");
        }
        String ref = blankToNull(organizerRef);
        if (races.markSubmitted(id, user.userId(), ref) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "This entry has just been updated by someone else");
        }
        String to = e.organizer() == null ? "the organizer" : e.organizer();
        users.writeAudit(user.userId(), "RACE_SUBMITTED", "RACE_REGISTRATION", id, "SUCCESS", ip,
                e.horseName() + " in " + e.raceName() + " on " + e.raceDate() + " submitted to " + to
                        + (ref == null ? "" : ", ref " + ref));
        Notice notice = new Notice("RACE_SUBMITTED", "Race entry submitted",
                clip(e.horseName() + "'s entry for " + e.raceName() + " on " + e.raceDate().format(DAY)
                        + " was submitted to " + to + (ref == null ? "" : " (ref " + ref + ")") + ".", 500),
                Notice.HORSE, e.horseId());
        notifications.notifyUser(e.ownerId(), notice);
        if (e.trainerId() != null) {
            notifications.notifyHorseTrainer(e.horseId(), user, notice);
        }
        return require(id);
    }

    private static String organizerOf(RaceModels.RaceEvent race) {
        return race.organizer() == null ? "the organizer" : race.organizer();
    }

    /**
     * V55: Head Trainer tư vấn rút ngựa khỏi một suất chủ đã xác nhận. Lý do lưu vào suất đua,
     * chủ ngựa nhận thông báo và tự quyết (bấm Withdraw hoặc giữ nguyên).
     */
    @Transactional
    public RaceModels.Entry recommendWithdrawal(int id, String reason, AuthenticatedUser user, String ip) {
        RaceModels.Entry e = require(id);
        if (!managedBy(e.trainerId(), user)) {
            throw notYourHorse(e.horseName());
        }
        if (!"APPROVED".equals(e.approvalStatus()) || e.result() != null) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Only a confirmed entry without a result can get a withdrawal recommendation");
        }
        if (e.raceDate().isBefore(LocalDate.now())) {
            throw new ApiException(HttpStatus.CONFLICT, "The race has already run. Record its result instead");
        }
        String why = clip(reason.trim(), 500);
        if (races.setWithdrawAdvice(id, why, user.userId()) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "This entry has just been updated by someone else");
        }
        users.writeAudit(user.userId(), "RACE_WITHDRAW_ADVICE", "RACE_REGISTRATION", id, "SUCCESS", ip,
                "Advised withdrawing " + e.horseName() + " from " + e.raceName() + ": " + why);
        notifications.notifyUser(e.ownerId(), new Notice("RACE_WITHDRAW_ADVICE",
                "Trainer recommends withdrawing",
                clip("Your Head Trainer recommends withdrawing " + e.horseName() + " from " + e.raceName()
                        + " on " + e.raceDate().format(DAY) + ": " + why
                        + ". You decide: withdraw it from My races, or keep the entry.", 500),
                Notice.HORSE, e.horseId()));
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
                + (r.organizer() == null ? "" : ", run by " + r.organizer())
                + (r.distanceM() == null ? "" : ", " + r.distanceM() + " m")
                + (r.venue() == null ? "" : ", " + r.venue())
                + (r.entryDeadline() == null ? "" : ", entries close " + r.entryDeadline())
                + (r.maxEntries() == null ? "" : ", max " + r.maxEntries())
                + (r.entryFee() == null ? "" : ", fee " + r.entryFee().stripTrailingZeros().toPlainString());
    }

    public RaceModels.Summary summary(int year) {
        // Tổng hợp thành tích cả CLB trong một năm: đếm theo state, theo result, cộng tiền thưởng, rồi gom theo
        // từng ngựa (chỉ tính suất RAN_OR_WILL_RUN: FINISHED / UPCOMING / AWAITING_RESULT) và xếp theo tiền thưởng giảm dần.
        List<RaceModels.Entry> all = races.find(year, null, null);
        Map<Integer, int[]> counts = new LinkedHashMap<>();       // entries, wins, places
        Map<Integer, BigDecimal> prizes = new LinkedHashMap<>();
        Map<Integer, RaceModels.Entry> first = new LinkedHashMap<>();
        int finished = 0, wins = 0, places = 0, unplaced = 0, scratched = 0, upcoming = 0,
            awaitingResult = 0, pendingApproval = 0;
        BigDecimal total = BigDecimal.ZERO;

        for (RaceModels.Entry e : all) {
            BigDecimal prize = e.prizeMoney() == null ? BigDecimal.ZERO : e.prizeMoney();
            switch (e.state()) {
                case "SCRATCHED" -> scratched++;
                case "FINISHED" -> finished++;
                // A past race with no result yet is not "upcoming" - see RaceModels.Summary.
                case "AWAITING_RESULT" -> awaitingResult++;
                case "UPCOMING" -> upcoming++;
                case "PENDING_APPROVAL" -> pendingApproval++;
                default -> {}   // REJECTED, EXPIRED: con ngựa không chạy, không đếm
            }
            if ("WON".equals(e.result())) wins++;
            if ("PLACED".equals(e.result())) places++;
            if ("UNPLACED".equals(e.result())) unplaced++;
            total = total.add(prize);
            if (!RAN_OR_WILL_RUN.contains(e.state())) continue;

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
        return new RaceModels.Summary(year, finished + upcoming + awaitingResult, finished, wins, places,
                unplaced, scratched, upcoming, awaitingResult, pendingApproval, total, perHorse);
    }

    /** Suất chủ đã đồng ý: đếm vào thành tích (Summary.entries, Performance by horse). */
    private static final Set<String> RAN_OR_WILL_RUN = Set.of("FINISHED", "UPCOMING", "AWAITING_RESULT");

    /* ---------------- helper ---------------- */

    /** Blocking problems (first one is shown) and warnings that do not block. */
    // Record private lồng trong service: chỉ dùng nội bộ để trả hai danh sách cùng lúc.
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
        // Điều kiện CHẶN: PEDIGREE, đã rút, thiếu ngày sinh, ngoài tuổi đua 2..10, sức khoẻ khác ELIGIBLE, đang bị
        // khoá tập, còn báo cáo sự cố mở, chưa có hoặc quá hạn tiêm phòng, không có lượt chạy thử hoàn thành trong
        // app.race.trial-window-days (30), chạy giải khác cách dưới app.race.min-rest-days (14).
        // (Từ bản sửa Flow 5, thiếu tiêm phòng / chạy thử chuyển từ cảnh báo sang chặn; danh sách warnings hiện
        // không còn mục nào được thêm, chỉ giữ lại để không đổi khuôn API.)
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
        // Hết hạn hợp đồng mà chưa gia hạn: không nhận giáo án mới, cũng không đăng ký giải mới.
        // Còn hạn nhưng hết trước ngày đua, hoặc chủ đã xin rút ngựa trước ngày đua: xem bên dưới.
        if (horses.hasExpiredContract(h.horseId())) {
            problems.add(h.horseName() + "'s stay at the club has ended. Renew it before entering a race");
        }
        if (horses.isWaitingForTakeover(h.horseId())) {
            problems.add(h.horseName() + " has a new owner who must sign the stay contract before entering a race");
        }
        /* The horse must still be at the club on race day: its stay runs past it, and it is
           not leaving before it. Checked again when the owner confirms (approveEntry). */
        if (race != null) {
            horses.stayEnd(h.horseId())
                    .filter(end -> end.isBefore(race.raceDate()))
                    .ifPresent(end -> problems.add(h.horseName() + "'s stay at the club ends on " + end.format(DAY)
                            + ", before the race. Renew the stay first"));
            horses.pendingLeaveOn(h.horseId())
                    .filter(leave -> !leave.isAfter(race.raceDate()))
                    .ifPresent(leave -> problems.add(h.horseName() + " leaves the club on " + leave.format(DAY)
                            + " (withdrawal requested)"));
        }
        if (h.dateOfBirth() == null) {
            problems.add(h.horseName() + " has no date of birth on record");
        } else {
            int age = HorsePolicy.ageOf(h.dateOfBirth());
            if (age < HorsePolicy.FOAL_BELOW || age > HorsePolicy.RACING_MAX_AGE) {
                problems.add(h.horseName() + " is " + HorsePolicy.ageInMonths(h.dateOfBirth(), LocalDate.now())
                        + " months old; racing age is " + (HorsePolicy.FOAL_BELOW * 12) + " to "
                        + ((HorsePolicy.RACING_MAX_AGE + 1) * 12 - 1) + " months");
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
        /* #3: tiêm phòng. OVERDUE và NEVER đều chặn. NEVER trước đây chỉ cảnh báo,
           nhưng màn Enter a horse đã chặn nó (commit 880540b) và nhóm chốt giữ luật chặn:
           ngựa mới về CLB thì nhập lịch sử tiêm phòng trước khi cho đua. */
        preventive.statusOf(h.horseId(), "VACCINATION").ifPresent(v -> {
            if ("OVERDUE".equals(v.careStatus())) {
                problems.add(h.horseName() + "'s vaccination is overdue (was due " + v.nextDueDate() + ")");
            } else if ("NEVER".equals(v.careStatus())) {
                problems.add(h.horseName() + " has no vaccination on record");
            }
        });
        /* #3: rest between races. */
        if (race != null) {
            races.nearbyEntry(h.horseId(), race.raceDate(), minRestDays, race.raceId()).ifPresent(other ->
                    problems.add(h.horseName() + " runs in " + other.raceName() + " on "
                            + other.raceDate().format(DAY) + "; races must be at least " + minRestDays
                            + " days apart"));
        }
        /* #3: trial run. Cũng chặn (cùng quyết định như tiêm phòng ở trên). Kiểm lại
           lúc chủ duyệt, nên đề xuất để lâu tới khi trial run quá 30 ngày thì phải
           chạy thử lại mới duyệt được. */
        if (!races.hasRecentTrial(h.horseId(), trialWindowDays)) {
            problems.add(h.horseName() + " has no completed trial run in the last " + trialWindowDays + " days");
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
