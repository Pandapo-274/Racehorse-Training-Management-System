package racehorse.backend.supply;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import racehorse.backend.auth.ApiException;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.UserRepository;
import racehorse.backend.notification.Notice;
import racehorse.backend.notification.NotificationService;

@Service
public class SupplyService {

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

    @Transactional
    public SupplyModels.Supply create(SupplyModels.SupplyRequest r, AuthenticatedUser user, String ip) {
        int id = repo.insert(r, user.userId());
        users.writeAudit(user.userId(), "SUPPLY_CREATE", "SUPPLY_STOCK", id, "SUCCESS", ip);
        SupplyModels.Supply s = get(id);
        warnIfLow(null, s, user);
        return s;
    }

    @Transactional
    public SupplyModels.Supply update(int id, SupplyModels.SupplyRequest r, AuthenticatedUser user, String ip) {
        SupplyModels.Supply before = get(id);
        repo.update(id, r, user.userId());
        users.writeAudit(user.userId(), "SUPPLY_UPDATE", "SUPPLY_STOCK", id, "SUCCESS", ip);
        SupplyModels.Supply after = get(id);
        warnIfLow(before, after, user);
        return after;
    }

    @Transactional
    public SupplyModels.Supply count(int id, BigDecimal qty, AuthenticatedUser user, String ip) {
        SupplyModels.Supply before = get(id);
        repo.updateQty(id, qty, user.userId());
        users.writeAudit(user.userId(), "SUPPLY_COUNT", "SUPPLY_STOCK", id, "SUCCESS", ip);
        SupplyModels.Supply after = get(id);
        warnIfLow(before, after, user);
        return after;
    }

    /** Chỉ báo lúc vừa TỤT xuống dưới định mức, không báo lại mỗi lần đếm khi vẫn còn thiếu. */
    private void warnIfLow(SupplyModels.Supply before, SupplyModels.Supply after, AuthenticatedUser actor) {
        boolean wasLow = before != null && before.needsRestock();
        if (after.needsRestock() && !wasLow) {
            notifications.notifyRole("CLUB_MANAGER", actor, new Notice(
                    "SUPPLY_LOW", "Restock suggested",
                    after.supplyName() + " (" + (after.area() == null ? "no area" : after.area()) + "): "
                            + after.currentQty().stripTrailingZeros().toPlainString() + " " + after.unit()
                            + " left, minimum is " + after.minThreshold().stripTrailingZeros().toPlainString()
                            + ".",
                    null, null));
        }
    }

    private SupplyModels.Supply get(int id) {
        return repo.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Supply #" + id + " not found"));
    }
}
