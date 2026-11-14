package racehorse.backend.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import racehorse.backend.entity.Notice;
import racehorse.backend.entity.SupplyModels;
import racehorse.backend.exception.ApiException;
import racehorse.backend.repository.SupplyRepository;
import racehorse.backend.repository.UserRepository;
import racehorse.backend.security.AuthenticatedUser;

/**
 * Flow 4 - the store (SUPPLY_STOCK) and its ledger (SUPPLY_MOVEMENT, V21).
 *
 * current_qty is the live balance; every change to it writes one movement:
 *   RECEIVE   a delivery (Manager) or the opening quantity of a new item
 *   CONSUME   used by a task - a FEEDING task takes the horse's ration
 *   REVERSAL  given back when that task is reopened
 *   ADJUST    a count found a different number
 *   WASTE     thrown away (spoiled, expired, broken)
 * The catalogue edit (PUT /{id}) no longer touches the quantity at all.
 */
@Service
public class SupplyService {

    /** Medicine expiring within this many days goes into the daily digest. */
    static final int EXPIRY_WARNING_DAYS = 30;

    private final SupplyRepository repo;
    private final UserRepository users;
    private final NotificationService notifications;

    public SupplyService(SupplyRepository repo, UserRepository users, NotificationService notifications) {
        this.repo = repo;
        this.users = users;
        this.notifications = notifications;
    }

    public List<SupplyModels.Supply> list(String area, String category, boolean lowOnly) {
        String cat = category == null || category.isBlank() ? null : category.trim().toUpperCase(Locale.ROOT);
        String a = area == null || area.isBlank() ? null : area.trim();
        return repo.find(a, cat, lowOnly);
    }

    public Optional<SupplyModels.Supply> find(int id) {
        return repo.findById(id);
    }

    public List<SupplyModels.Movement> movements(int id) {
        get(id);
        return repo.movements(id, 100);
    }

    /* ---------------- Catalogue (Manager) ---------------- */

    @Transactional
    public SupplyModels.Supply create(SupplyModels.SupplyRequest r, AuthenticatedUser user, String ip) {
        if (r.currentQty() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid data",
                    Map.of("currentQty", "Quantity is required"));
        }
        checkName(r, null);
        int id;
        try {
            id = repo.insert(r, user.userId());
        } catch (DuplicateKeyException e) {
            throw duplicate(r);
        }
        if (r.currentQty().signum() > 0) {
            repo.insertMovement(id, "RECEIVE", r.currentQty(), r.currentQty(), null, null,
                    "Opening quantity", user.userId());
        }
        SupplyModels.Supply s = get(id);
        users.writeAudit(user.userId(), "SUPPLY_CREATE", "SUPPLY_STOCK", id, "SUCCESS", ip,
                s.supplyName() + where(s) + ": " + plain(s.currentQty()) + " " + s.unit()
                        + ", minimum " + plain(s.minThreshold()));
        warnIfLow(null, s, user);
        return s;
    }

    /**
     * Edits name, category, unit, minimum, area, expiry. The quantity in the
     * request is ignored - a count, a delivery or a task changes it.
     */
    @Transactional
    public SupplyModels.Supply update(int id, SupplyModels.SupplyRequest r, AuthenticatedUser user, String ip) {
        SupplyModels.Supply before = get(id);
        checkName(r, id);
        /* A ration line's quantity is read in this item's unit. Changing kg to
           bales under a live ration would make every feed take a different
           amount without anyone touching the ration. */
        if (!before.unit().equalsIgnoreCase(r.unit().trim())) {
            int lines = repo.rationLinesUsing(id);
            if (lines > 0) {
                throw new ApiException(HttpStatus.CONFLICT, lines + " ration line(s) measure "
                        + before.supplyName() + " in " + before.unit()
                        + ". Change those rations first, then the unit");
            }
        }
        if (!"FEED".equals(r.category()) && repo.rationLinesUsing(id) > 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    before.supplyName() + " is used in rations, so it must stay a FEED item");
        }
        try {
            repo.update(id, r, user.userId());
        } catch (DuplicateKeyException e) {
            throw duplicate(r);
        }
        SupplyModels.Supply after = get(id);
        users.writeAudit(user.userId(), "SUPPLY_UPDATE", "SUPPLY_STOCK", id, "SUCCESS", ip,
                before.supplyName() + " -> " + after.supplyName() + where(after)
                        + ", unit " + before.unit() + " -> " + after.unit()
                        + ", minimum " + plain(before.minThreshold()) + " -> " + plain(after.minThreshold())
                        + (after.expiryDate() == null ? "" : ", expires " + after.expiryDate()));
        warnIfLow(before, after, user);
        return after;
    }

    /* ---------------- Quantity: count, receive, waste ---------------- */

    @Transactional
    public SupplyModels.Supply count(int id, BigDecimal qty, AuthenticatedUser user, String ip) {
        return count(id, qty, null, null, user, ip);
    }

    /** Why a count can be higher than the record (stock does not appear by itself). */
    static final Map<String, String> COUNT_UP_TEXT = Map.of(
            "RECOUNT", "earlier count was wrong",
            "RETURNED", "returned to the store",
            "FOUND", "found misplaced",
            "NOT_USED", "taken by the system but not used",
            "OTHER", "other");

    /**
     * A shelf count sets the quantity and writes an ADJUST line for the difference.
     * Lower or equal: no reason needed (wear, loss). HIGHER: stock does not appear by
     * itself, so a reason is required (and a note for OTHER); it goes into the ledger
     * line, and the club manager is told when someone else counted. Refusing higher
     * counts outright would push people to record a fake delivery instead.
     */
    @Transactional
    public SupplyModels.Supply count(int id, BigDecimal qty, String reason, String note,
                                     AuthenticatedUser user, String ip) {
        SupplyModels.Supply before = get(id);
        /* Medicine is a controlled stock: a groom may report it (POST /{id}/report),
           but only the club manager counts it (review #11; the vet no longer runs the store). */
        if ("GROOM".equals(user.role()) && "MEDICINE".equals(before.category())) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Medicine is counted by the club manager");
        }
        boolean higher = qty.compareTo(before.currentQty()) > 0;
        String why = reason == null || reason.isBlank() ? null : reason.trim().toUpperCase(Locale.ROOT);
        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        if (higher && (why == null || !COUNT_UP_TEXT.containsKey(why))) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "The count is higher than the record (" + plain(before.currentQty()) + " " + before.unit()
                            + "). Say why there is more",
                    Map.of("reason", "Choose why there is more than on record"));
        }
        if (higher && "OTHER".equals(why) && cleanNote == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Say why there is more than on record",
                    Map.of("note", "Describe why there is more"));
        }
        String moveReason = higher
                ? "Stock count, more than record: " + COUNT_UP_TEXT.get(why) + (cleanNote == null ? "" : " - " + cleanNote)
                : "Stock count";
        BigDecimal[] range = repo.setQty(id, qty, user.userId()).orElseThrow(() -> notFound(id));
        BigDecimal delta = range[1].subtract(range[0]);
        if (delta.signum() != 0) {
            repo.insertMovement(id, "ADJUST", delta, range[1], null, null, moveReason, user.userId());
        }
        SupplyModels.Supply after = get(id);
        users.writeAudit(user.userId(), "SUPPLY_COUNT", "SUPPLY_STOCK", id, "SUCCESS", ip,
                after.supplyName() + where(after) + ": " + plain(range[0]) + " -> " + plain(range[1])
                        + " " + after.unit() + (higher ? ". " + moveReason : ""));
        if (higher && delta.signum() > 0 && !"CLUB_MANAGER".equals(user.role())) {
            String who = users.findById(user.userId()).map(u -> u.fullName()).orElse(user.username());
            String text = who + " counted " + plain(range[1]) + " " + after.unit() + " of " + after.supplyName()
                    + where(after) + ", the record was " + plain(range[0]) + ". Reason: " + COUNT_UP_TEXT.get(why)
                    + (cleanNote == null ? "" : " - " + cleanNote) + ".";
            notifications.notifyRole("CLUB_MANAGER", user, new Notice("SUPPLY_COUNT_UP",
                    "Count higher than record: " + after.supplyName(),
                    text.length() <= 500 ? text : text.substring(0, 497) + "...", null, null));
        }
        warnIfLow(before, after, user);
        return after;
    }

    /** A delivery arrived (Club Manager). */
    @Transactional
    public SupplyModels.Supply receive(int id, SupplyModels.ReceiveRequest r, AuthenticatedUser user, String ip) {
        SupplyModels.Supply before = get(id);
        String customNote = r.note() != null && !r.note().isBlank() ? r.note().trim() : "";
        BigDecimal price = r.unitPrice() != null && r.unitPrice().compareTo(BigDecimal.ZERO) > 0
                ? r.unitPrice() : (before.unitPrice() != null ? before.unitPrice() : BigDecimal.ZERO);
        String unit = r.unit() != null && !r.unit().isBlank() ? r.unit().trim() : before.unit();
        String area = r.area() != null && !r.area().isBlank() ? r.area().trim() : before.area();

        BigDecimal[] range = repo.addWithDetails(id, r.qty(), r.unitPrice(), r.unit(), r.area(), user.userId())
                .orElseThrow(() -> notFound(id));

        String moveReason = "Restocked / Purchased (+ " + plain(r.qty()) + " " + unit + ") - " + plain(price) + " VND"
                + (customNote.isEmpty() ? "" : " - " + customNote);
        repo.insertMovement(id, "RECEIVE", r.qty(), range[1], null, null, moveReason, user.userId());

        SupplyModels.Supply after = get(id);
        users.writeAudit(user.userId(), "SUPPLY_RECEIVE", "SUPPLY_STOCK", id, "SUCCESS", ip,
                after.supplyName() + where(after) + ": +" + plain(r.qty()) + " " + after.unit()
                        + " (" + plain(range[0]) + " -> " + plain(range[1]) + "). " + moveReason);

        // Notify Club Manager (do NOT notify horse owner)
        BigDecimal totalCost = r.qty().multiply(price);
        String costText = totalCost.compareTo(BigDecimal.ZERO) > 0 ? " Total cost: " + plain(totalCost) + " VND." : "";
        notifications.notifyRole("CLUB_MANAGER", user, new Notice(
                "SUPPLY_RESTOCKED",
                "Supply restocked: " + after.supplyName(),
                user.role() + " " + user.username() + " restocked +" + plain(r.qty()) + " " + after.unit()
                        + " of " + after.supplyName() + " to " + (after.area() != null ? after.area() : "Storage") + "." + costText + (customNote.isEmpty() ? "" : " Note: " + customNote),
                null, null));

        return after;
    }

    /** Thrown away: spoiled feed, expired medicine (Club Manager). */
    @Transactional
    public SupplyModels.Supply waste(int id, SupplyModels.WasteRequest r, AuthenticatedUser user, String ip) {
        SupplyModels.Supply before = get(id);
        if (r.qty().compareTo(before.currentQty()) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Only " + plain(before.currentQty()) + " "
                    + before.unit() + " of " + before.supplyName() + " in stock");
        }
        BigDecimal[] range = repo.takeUpTo(id, r.qty(), user.userId()).orElseThrow(() -> notFound(id));
        BigDecimal taken = range[0].subtract(range[1]);
        if (taken.signum() > 0) {
            repo.insertMovement(id, "WASTE", taken.negate(), range[1], null, null, "Waste / Discarded: " + r.reason().trim(),
                    user.userId());
        }
        SupplyModels.Supply after = get(id);
        users.writeAudit(user.userId(), "SUPPLY_WASTE", "SUPPLY_STOCK", id, "SUCCESS", ip,
                after.supplyName() + where(after) + ": -" + plain(taken) + " " + after.unit()
                        + ". Reason: " + r.reason().trim());
        warnIfLow(before, after, user);
        return after;
    }

    /* ---------------- Reported by staff (#spec: propose a restock) ---------------- */

    private static final Map<String, String> REPORT_TEXT = Map.of(
            "LOW", "running low", "DAMAGED", "damaged or spoiled", "EXPIRED", "expired", "OTHER", "problem");

    /**
     * A groom flags an item: the manager (and, for medicine, the vets) get a bell
     * at once instead of waiting for the next count or the 07:05 check. Nothing
     * in the store changes; the manager restocks, counts or throws away.
     *
     * @return what to show the reporter.
     */
    @Transactional
    public String report(int id, SupplyModels.ProblemReport r, AuthenticatedUser user, String ip) {
        SupplyModels.Supply item = get(id);
        String note = r.note() == null || r.note().isBlank() ? null : r.note().trim();
        if ("OTHER".equals(r.reason()) && note == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Say what is wrong",
                    Map.of("note", "Describe the problem"));
        }
        String who = users.findById(user.userId()).map(u -> u.fullName()).orElse(user.username());
        String what = REPORT_TEXT.get(r.reason());
        String text = who + ": " + item.supplyName() + where(item) + " is " + what
                + " (" + plain(item.currentQty()) + " " + item.unit() + " on record)."
                + (r.qty() == null ? "" : " Needs about " + plain(r.qty()) + " " + item.unit() + ".")
                + (note == null ? "" : " " + note);
        Notice notice = new Notice("SUPPLY_REPORTED",
                ("LOW".equals(r.reason()) ? "Restock requested: " : "Stock problem: ") + item.supplyName(),
                text.length() <= 500 ? text : text.substring(0, 497) + "...", null, null);
        notifications.notifyRole("CLUB_MANAGER", user, notice);
        boolean medicine = "MEDICINE".equals(item.category());
        if (medicine) {
            notifications.notifyRole("VETERINARIAN", user, notice);
        }
        users.writeAudit(user.userId(), "SUPPLY_REPORT", "SUPPLY_STOCK", id, "SUCCESS", ip,
                item.supplyName() + where(item) + ": " + what
                        + (r.qty() == null ? "" : ", needs " + plain(r.qty()) + " " + item.unit())
                        + (note == null ? "" : ". " + note));
        return "Sent to the club manager" + (medicine ? " and the veterinarians" : "") + ".";
    }

    /* ---------------- Used by tasks (CareService) ---------------- */

    /**
     * Takes qty for a task. Runs inside the caller's transaction (no
     * @Transactional here, same as NotificationService), so if the task fails
     * the stock goes back too.
     *
     * If the store holds less than qty, it takes what there is, warns every
     * Club Manager and does NOT block the groom: the number on the shelf is
     * counted by hand and can be wrong, and a horse is not left unfed because
     * the ledger says the bin is empty.
     *
     * @return what was actually taken (may be less than qty).
     */
    public BigDecimal consume(int supplyId, BigDecimal qty, String refType, int refId,
                              String reason, AuthenticatedUser user) {
        SupplyModels.Supply before = repo.findById(supplyId).orElse(null);
        if (before == null || qty == null || qty.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal[] range = repo.takeUpTo(supplyId, qty, user.userId()).orElse(null);
        if (range == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal taken = range[0].subtract(range[1]);
        if (taken.signum() > 0) {
            repo.insertMovement(supplyId, "CONSUME", taken.negate(), range[1], refType, refId, reason,
                    user.userId());
        }
        if (taken.compareTo(qty) < 0) {
            notifications.notifyRole("CLUB_MANAGER", user, new Notice(
                    "SUPPLY_SHORTFALL", "Stock ran out",
                    before.supplyName() + where(before) + ": " + reason + " needed " + plain(qty) + " "
                            + before.unit() + ", only " + plain(taken) + " was in stock. Count and restock.",
                    null, null));
        }
        repo.findById(supplyId).ifPresent(after -> warnIfLow(before, after, user));
        return taken;
    }

    /** Gives back what a reopened task had taken. */
    public void giveBack(int supplyId, BigDecimal qty, String refType, int refId,
                        String reason, AuthenticatedUser user) {
        if (qty == null || qty.signum() <= 0) {
            return;
        }
        repo.add(supplyId, qty, user.userId()).ifPresent(r ->
                repo.insertMovement(supplyId, "REVERSAL", qty, r[1], refType, refId, reason, user.userId()));
    }

    public Map<Integer, BigDecimal> netConsumedBy(String refType, int refId) {
        return repo.netConsumedBy(refType, refId);
    }

    /* ---------------- Daily digest (#12) ---------------- */

    /**
     * One notice per Club Manager per day listing every item that is still
     * below its minimum, or is medicine that has expired or expires soon.
     * The old warnIfLow fired only at the moment an item dropped below the
     * minimum; miss that one bell and nobody was told again.
     *
     * Idempotent: SUPPLY_STOCK.last_reminded_on (V24) is set in the same
     * transaction, so a second run on the same day finds nothing.
     *
     * @return number of items in the digest.
     */
    @Transactional
    public int sendDailyDigest(LocalDate today) {
        List<SupplyModels.Supply> due = repo.dueForReminder(today, EXPIRY_WARNING_DAYS);
        if (due.isEmpty()) {
            return 0;
        }
        notifications.notifyRole("CLUB_MANAGER", null, new Notice(
                "SUPPLY_DIGEST", "Store check: " + due.size() + " item(s) need attention",
                digestText(due, today), null, null));
        /* The vets use the medicine and prescribing takes it from this store, so
           they get the medicine part of the same check (they used to get nothing
           and only found out when a prescription was refused). */
        List<SupplyModels.Supply> medicine = due.stream().filter(s -> "MEDICINE".equals(s.category())).toList();
        if (!medicine.isEmpty()) {
            notifications.notifyRole("VETERINARIAN", null, new Notice(
                    "SUPPLY_DIGEST", "Medicine check: " + medicine.size() + " item(s) need attention",
                    digestText(medicine, today), null, null));
        }
        repo.markReminded(due.stream().map(SupplyModels.Supply::supplyId).toList(), today);
        return due.size();
    }

    /** "Below minimum: … Expired: … Expires soon: …", at most 500 characters. */
    private static String digestText(List<SupplyModels.Supply> items, LocalDate today) {
        Map<String, List<String>> lines = new LinkedHashMap<>();
        for (SupplyModels.Supply s : items) {
            if (s.needsRestock()) {
                lines.computeIfAbsent("Below minimum", k -> new java.util.ArrayList<>())
                        .add(s.supplyName() + " " + plain(s.currentQty()) + "/" + plain(s.minThreshold()) + " " + s.unit());
            }
            if (s.expiryDate() != null && "MEDICINE".equals(s.category())
                    && !s.expiryDate().isAfter(today.plusDays(EXPIRY_WARNING_DAYS)) && s.currentQty().signum() > 0) {
                lines.computeIfAbsent(s.expired() ? "Expired" : "Expires soon", k -> new java.util.ArrayList<>())
                        .add(s.supplyName() + " (" + s.expiryDate() + ")");
            }
        }
        StringBuilder msg = new StringBuilder();
        lines.forEach((head, names) -> {
            if (msg.length() > 0) msg.append(" ");
            msg.append(head).append(": ").append(String.join(", ", names)).append(".");
        });
        return msg.length() <= 500 ? msg.toString() : msg.substring(0, 497) + "...";
    }

    /* ---------------- helpers ---------------- */

    /**
     * Fires only when an item first drops below its minimum; the daily digest repeats it.
     * Medicine also goes to the vets: they prescribe from this stock.
     */
    private void warnIfLow(SupplyModels.Supply before, SupplyModels.Supply after, AuthenticatedUser actor) {
        boolean wasLow = before != null && before.needsRestock();
        if (after.needsRestock() && !wasLow) {
            Notice notice = new Notice(
                    "SUPPLY_LOW", "Restock suggested",
                    after.supplyName() + " (" + (after.area() == null ? "no area" : after.area()) + "): "
                            + plain(after.currentQty()) + " " + after.unit()
                            + " left, minimum is " + plain(after.minThreshold()) + ".",
                    null, null);
            notifications.notifyRole("CLUB_MANAGER", actor, notice);
            if ("MEDICINE".equals(after.category())) {
                notifications.notifyRole("VETERINARIAN", actor, notice);
            }
        }
    }

    /** V24: one name per area. Checked here for a clean 409; the UNIQUE index backs it up. */
    private void checkName(SupplyModels.SupplyRequest r, Integer exceptId) {
        if (repo.nameTaken(r.supplyName(), r.area(), exceptId)) {
            throw duplicate(r);
        }
        if (r.expiryDate() != null && !"MEDICINE".equals(r.category())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid data",
                    Map.of("expiryDate", "Only medicine has an expiry date"));
        }
    }

    private static ApiException duplicate(SupplyModels.SupplyRequest r) {
        String area = r.area() == null || r.area().isBlank() ? "with no area" : "in " + r.area().trim();
        return new ApiException(HttpStatus.CONFLICT, r.supplyName().trim() + " already exists " + area,
                Map.of("supplyName", "Supply already exists"));
    }

    private static String where(SupplyModels.Supply s) {
        return s.area() == null ? "" : " (" + s.area() + ")";
    }

    private static String plain(BigDecimal n) {
        return n == null ? "-" : n.stripTrailingZeros().toPlainString();
    }

    private static ApiException notFound(int id) {
        return new ApiException(HttpStatus.NOT_FOUND, "Supply #" + id + " not found");
    }

    private SupplyModels.Supply get(int id) {
        return repo.findById(id).orElseThrow(() -> notFound(id));
    }
}
