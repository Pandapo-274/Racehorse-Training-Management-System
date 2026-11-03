package racehorse.backend.supply;

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

import racehorse.backend.auth.ApiException;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.UserRepository;
import racehorse.backend.notification.Notice;
import racehorse.backend.notification.NotificationService;

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
        SupplyModels.Supply before = get(id);
        /* Medicine is a controlled stock: a groom may report it missing via an
           incident, but the count is made by a vet or the manager (review #11). */
        if ("GROOM".equals(user.role()) && "MEDICINE".equals(before.category())) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Medicine is counted by a veterinarian or the club manager");
        }
        BigDecimal[] range = repo.setQty(id, qty, user.userId()).orElseThrow(() -> notFound(id));
        BigDecimal delta = range[1].subtract(range[0]);
        if (delta.signum() != 0) {
            repo.insertMovement(id, "ADJUST", delta, range[1], null, null, "Stock count", user.userId());
        }
        SupplyModels.Supply after = get(id);
        users.writeAudit(user.userId(), "SUPPLY_COUNT", "SUPPLY_STOCK", id, "SUCCESS", ip,
                after.supplyName() + where(after) + ": " + plain(range[0]) + " -> " + plain(range[1])
                        + " " + after.unit());
        warnIfLow(before, after, user);
        return after;
    }

    /** A delivery arrived (Manager). */
    @Transactional
    public SupplyModels.Supply receive(int id, SupplyModels.ReceiveRequest r, AuthenticatedUser user, String ip) {
        get(id);
        String note = r.note() == null || r.note().isBlank() ? "Delivery" : r.note().trim();
        BigDecimal[] range = repo.add(id, r.qty(), user.userId()).orElseThrow(() -> notFound(id));
        repo.insertMovement(id, "RECEIVE", r.qty(), range[1], null, null, note, user.userId());
        SupplyModels.Supply after = get(id);
        users.writeAudit(user.userId(), "SUPPLY_RECEIVE", "SUPPLY_STOCK", id, "SUCCESS", ip,
                after.supplyName() + where(after) + ": +" + plain(r.qty()) + " " + after.unit()
                        + " (" + plain(range[0]) + " -> " + plain(range[1]) + "). " + note);
        return after;
    }

    /** Thrown away: spoiled feed, expired medicine (Manager, Vet). */
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
            repo.insertMovement(id, "WASTE", taken.negate(), range[1], null, null, r.reason().trim(),
                    user.userId());
        }
        SupplyModels.Supply after = get(id);
        users.writeAudit(user.userId(), "SUPPLY_WASTE", "SUPPLY_STOCK", id, "SUCCESS", ip,
                after.supplyName() + where(after) + ": -" + plain(taken) + " " + after.unit()
                        + ". Reason: " + r.reason().trim());
        warnIfLow(before, after, user);
        return after;
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
        Map<String, List<String>> lines = new LinkedHashMap<>();
        for (SupplyModels.Supply s : due) {
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
        lines.forEach((head, items) -> {
            if (msg.length() > 0) msg.append(" ");
            msg.append(head).append(": ").append(String.join(", ", items)).append(".");
        });
        String text = msg.length() <= 500 ? msg.toString() : msg.substring(0, 497) + "...";
        notifications.notifyRole("CLUB_MANAGER", null, new Notice(
                "SUPPLY_DIGEST", "Store check: " + due.size() + " item(s) need attention", text, null, null));
        repo.markReminded(due.stream().map(SupplyModels.Supply::supplyId).toList(), today);
        return due.size();
    }

    /* ---------------- helpers ---------------- */

    /** Fires only when an item first drops below its minimum; the daily digest repeats it. */
    private void warnIfLow(SupplyModels.Supply before, SupplyModels.Supply after, AuthenticatedUser actor) {
        boolean wasLow = before != null && before.needsRestock();
        if (after.needsRestock() && !wasLow) {
            notifications.notifyRole("CLUB_MANAGER", actor, new Notice(
                    "SUPPLY_LOW", "Restock suggested",
                    after.supplyName() + " (" + (after.area() == null ? "no area" : after.area()) + "): "
                            + plain(after.currentQty()) + " " + after.unit()
                            + " left, minimum is " + plain(after.minThreshold()) + ".",
                    null, null));
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
