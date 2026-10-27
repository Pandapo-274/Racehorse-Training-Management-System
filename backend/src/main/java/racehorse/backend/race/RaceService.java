package racehorse.backend.race;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import racehorse.backend.auth.ApiException;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.UserRepository;
import racehorse.backend.horse.HorsePolicy;
import racehorse.backend.horse.HorseRepository;
import racehorse.backend.horse.HorseResponse;
import racehorse.backend.horse.HorseVitalsRepository;
import racehorse.backend.incident.IncidentRepository;
import racehorse.backend.notification.Notice;
import racehorse.backend.notification.NotificationService;

/**
 * Flow 5 - Head Trainer đăng ký ngựa dự giải, nhập kết quả, rút khỏi giải.
 *
 * Điều kiện đăng ký (cũng là điều kiện hiện ở danh sách "candidates"):
 *  - ngựa của câu lạc bộ (ACADEMY), chưa rút;
 *  - sức khoẻ ELIGIBLE, không bị khoá tập, không còn báo cáo sự cố chưa khám;
 *  - tuổi đua 2..10 (HorsePolicy.FOAL_BELOW, RACING_MAX_AGE);
 *  - ngày đua ở tương lai và ngựa chưa có giải khác cùng ngày.
 * Doanh thu giải đua = tổng tiền thưởng; không có hoá đơn hay thanh toán.
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

    public RaceService(RaceRepository races, HorseRepository horses, HorseVitalsRepository vitals,
                       IncidentRepository incidents, UserRepository users, NotificationService notifications) {
        this.races = races;
        this.horses = horses;
        this.vitals = vitals;
        this.incidents = incidents;
        this.users = users;
        this.notifications = notifications;
    }

    /** Chủ ngựa chỉ thấy ngựa của mình. */
    public List<RaceModels.Entry> list(Integer year, Integer horseId, AuthenticatedUser user) {
        Integer ownerId = "HORSE_OWNER".equals(user.role()) ? user.userId() : null;
        return races.find(year, horseId, ownerId);
    }

    public List<Integer> years() {
        return races.years();
    }

    public List<RaceModels.Candidate> candidates() {
        List<RaceModels.Candidate> out = new ArrayList<>();
        for (HorseResponse h : horses.findActive()) {
            if (!HorsePolicy.ACADEMY.equals(HorsePolicy.normalizeType(h.recordType()))) continue;
            Integer age = h.dateOfBirth() == null ? null : HorsePolicy.ageOf(h.dateOfBirth());
            out.add(new RaceModels.Candidate(h.horseId(), h.horseName(), h.stallNo(), h.ownerName(),
                    age, h.status(), problemOf(h)));
        }
        return out;
    }

    @Transactional
    public RaceModels.Entry register(RaceModels.RegisterRequest r, AuthenticatedUser user, String ip) {
        HorseResponse h = horses.findById(r.horseId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Horse #" + r.horseId() + " not found"));
        String problem = problemOf(h);
        if (problem != null) {
            throw new ApiException(HttpStatus.CONFLICT, problem);
        }
        if (!r.raceDate().isAfter(LocalDate.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Race date must be in the future",
                    Map.of("raceDate", "Choose a day after today"));
        }
        if (r.raceDate().isAfter(LocalDate.now().plusYears(1))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Race date is too far ahead",
                    Map.of("raceDate", "Register at most one year ahead"));
        }
        String name = r.raceName().trim().replaceAll("\\s+", " ");
        if (races.exists(h.horseId(), name, r.raceDate())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    h.horseName() + " is already registered for " + name + " on " + r.raceDate().format(DAY));
        }
        races.otherRaceOn(h.horseId(), r.raceDate()).ifPresent(other -> {
            throw new ApiException(HttpStatus.CONFLICT,
                    h.horseName() + " already runs in " + other + " on " + r.raceDate().format(DAY));
        });

        int id = races.insert(h.horseId(), name, r.raceDate(), user.userId(), blankToNull(r.note()));
        users.writeAudit(user.userId(), "RACE_REGISTER", "RACE_REGISTRATION", id, "SUCCESS", ip);
        notifications.notifyUser(h.ownerId(), new Notice(
                "RACE_REGISTERED", "Race entry",
                h.horseName() + " is entered in " + name + " on " + r.raceDate().format(DAY) + ".",
                Notice.HORSE, h.horseId()));
        return require(id);
    }

    /** Kết quả chỉ nhập được từ ngày đua trở đi; nhập lại được để sửa sai (có audit). */
    @Transactional
    public RaceModels.Entry recordResult(int id, RaceModels.ResultRequest r, AuthenticatedUser user, String ip) {
        RaceModels.Entry e = require(id);
        if ("SCRATCHED".equals(e.result())) {
            throw new ApiException(HttpStatus.CONFLICT, "This entry was scratched and has no result");
        }
        if (e.raceDate().isAfter(LocalDate.now())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "The race is on " + e.raceDate().format(DAY) + ". Record the result after it has run");
        }
        BigDecimal prize = r.prizeMoney() == null ? BigDecimal.ZERO : r.prizeMoney();
        if ("UNPLACED".equals(r.result()) && prize.signum() != 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "An unplaced horse earns no prize money",
                    Map.of("prizeMoney", "Set prize money to 0 for UNPLACED"));
        }
        String note = r.note() == null ? e.note() : blankToNull(r.note());
        if (races.saveResult(id, r.result(), prize, note) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "This entry has just been scratched");
        }
        users.writeAudit(user.userId(), "RACE_RESULT", "RACE_REGISTRATION", id, "SUCCESS", ip);
        notifications.notifyUser(e.ownerId(), new Notice(
                "RACE_RESULT", "Race result",
                e.horseName() + " in " + e.raceName() + ": " + r.result().toLowerCase(Locale.ROOT)
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
        if (e.raceDate().isBefore(LocalDate.now())) {
            throw new ApiException(HttpStatus.CONFLICT, "The race has already run. Record its result instead");
        }
        String note = clip("Scratched: " + reason.trim() + (e.note() == null ? "" : " | " + e.note()), 255);
        if (races.scratch(id, note) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "This entry has just been updated by someone else");
        }
        users.writeAudit(user.userId(), "RACE_SCRATCH", "RACE_REGISTRATION", id, "SUCCESS", ip);
        notifications.notifyUser(e.ownerId(), new Notice(
                "RACE_SCRATCHED", "Race entry withdrawn",
                e.horseName() + " was withdrawn from " + e.raceName() + ": " + reason.trim() + ".",
                Notice.HORSE, e.horseId()));
        return require(id);
    }

    public RaceModels.Summary summary(int year) {
        List<RaceModels.Entry> all = races.find(year, null, null);
        Map<Integer, int[]> counts = new LinkedHashMap<>();       // entries, wins, places
        Map<Integer, BigDecimal> prizes = new LinkedHashMap<>();
        Map<Integer, RaceModels.Entry> first = new LinkedHashMap<>();
        int finished = 0, wins = 0, places = 0, unplaced = 0, scratched = 0, upcoming = 0;
        BigDecimal total = BigDecimal.ZERO;

        for (RaceModels.Entry e : all) {
            BigDecimal prize = e.prizeMoney() == null ? BigDecimal.ZERO : e.prizeMoney();
            switch (e.state()) {
                case "SCRATCHED" -> scratched++;
                case "FINISHED" -> finished++;
                default -> upcoming++;
            }
            if ("WON".equals(e.result())) wins++;
            if ("PLACED".equals(e.result())) places++;
            if ("UNPLACED".equals(e.result())) unplaced++;
            total = total.add(prize);
            if ("SCRATCHED".equals(e.state())) continue;

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
                scratched, upcoming, total, perHorse);
    }

    /* ---------------- helper ---------------- */

    /** @return lý do không đăng ký được, hoặc null. */
    private String problemOf(HorseResponse h) {
        if (!HorsePolicy.ACADEMY.equals(HorsePolicy.normalizeType(h.recordType()))) {
            return h.horseName() + " is a pedigree-only record, not a club horse";
        }
        if (h.isWithdrawn()) {
            return h.horseName() + " has been withdrawn from the club";
        }
        if (h.dateOfBirth() == null) {
            return h.horseName() + " has no date of birth on record";
        }
        int age = HorsePolicy.ageOf(h.dateOfBirth());
        if (age < HorsePolicy.FOAL_BELOW || age > HorsePolicy.RACING_MAX_AGE) {
            return h.horseName() + " is " + age + " years old; racing age is "
                    + HorsePolicy.FOAL_BELOW + " to " + HorsePolicy.RACING_MAX_AGE;
        }
        if (!"ELIGIBLE".equals(h.status())) {
            return h.horseName() + " is not cleared to race (health status " + h.status() + ")";
        }
        if (vitals.findActiveLock(h.horseId()).isPresent()) {
            return h.horseName() + " has an active training lock";
        }
        if (incidents.findOpenReport(h.horseId()).isPresent()) {
            return h.horseName() + " has an incident report waiting for a veterinarian";
        }
        return null;
    }

    private RaceModels.Entry require(int id) {
        return races.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Race entry #" + id + " not found"));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
