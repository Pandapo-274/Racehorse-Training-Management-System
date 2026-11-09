package racehorse.backend.horse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import racehorse.backend.auth.ApiException;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.UserRepository;
import racehorse.backend.notification.Notice;
import racehorse.backend.notification.NotificationService;

/**
 * Club Manager actions on a horse record that change who holds it or whether
 * it is at the club - kept out of HorseService, which is about the record's
 * own data.
 *
 *  - transferOwner: the horse is sold or handed to another owner.
 *  - readmit: a withdrawn horse comes back to the club.
 */
@Service
public class HorseAdminService {

    private final HorseRepository horses;
    private final UserRepository users;
    private final NotificationService notifications;

    public HorseAdminService(HorseRepository horses, UserRepository users, NotificationService notifications) {
        this.horses = horses;
        this.users = users;
        this.notifications = notifications;
    }

    /**
     * Moves the horse to another Horse Owner account.
     *
     * Refused while anything is waiting for the CURRENT owner's decision (a
     * training plan or race entry to approve, a withdrawal request): the new
     * owner would inherit a decision they never saw, and the old one could still
     * answer it. Those must be settled first.
     *
     * Costs and race results already recorded stay on the horse; the owner
     * dashboard shows costs by current owner, so a sale with an open bill is a
     * business question for the club, said in the reason.
     */
    @Transactional
    public HorseResponse transferOwner(int horseId, int newOwnerId, String reason, AuthenticatedUser actor, String ip) {
        HorseResponse horse = get(horseId);
        if (horse.isWithdrawn()) {
            throw new ApiException(HttpStatus.CONFLICT, horse.horseName() + " has left the club. Re-admit it first");
        }
        if (horse.ownerId() == newOwnerId) {
            throw new ApiException(HttpStatus.BAD_REQUEST, horse.horseName() + " already belongs to this owner",
                    Map.of("ownerId", "Choose another owner"));
        }
        if (!horses.isActiveHorseOwner(newOwnerId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The new owner must be an active Horse Owner account",
                    Map.of("ownerId", "Choose an active Horse Owner"));
        }
        HorseRepository.PendingOnOwner pending = horses.pendingOnOwner(horseId);
        if (pending.any()) {
            List<String> parts = new ArrayList<>();
            if (pending.plans() > 0) parts.add(pending.plans() + " training plan(s) to approve");
            if (pending.raceEntries() > 0) parts.add(pending.raceEntries() + " race entr"
                    + (pending.raceEntries() == 1 ? "y" : "ies") + " to approve");
            if (pending.withdrawals() > 0) parts.add("a withdrawal request");
            throw new ApiException(HttpStatus.CONFLICT, "The current owner still has " + String.join(", ", parts)
                    + " for " + horse.horseName() + ". Settle them before the transfer");
        }
        String why = reason.trim();
        horses.updateOwner(horseId, newOwnerId);
        HorseResponse after = get(horseId);
        users.writeAudit(actor.userId(), "HORSE_OWNER_TRANSFER", "HORSE", horseId, "SUCCESS", ip,
                "owner: " + horse.ownerName() + " -> " + after.ownerName() + ". Reason: " + why);
        notifications.notifyUser(horse.ownerId(), new Notice("HORSE_TRANSFERRED", "Horse transferred",
                horse.horseName() + " now belongs to " + after.ownerName() + ". Reason: " + why,
                null, null));
        notifications.notifyUser(newOwnerId, new Notice("HORSE_TRANSFERRED", "Horse transferred to you",
                horse.horseName() + " (" + horse.registrationCode() + ") is now registered in your name.",
                Notice.HORSE, horseId));
        return after;
    }

    /**
     * A withdrawn club horse comes back: ACTIVE again with a stall. The health
     * status is kept as the vet last left it; plans and sessions start fresh
     * (withdrawal ended them). The old HORSE_WITHDRAWAL row stays as history.
     */
    @Transactional
    public HorseResponse readmit(int horseId, String rawStall, AuthenticatedUser actor, String ip) {
        HorseResponse horse = get(horseId);
        if (!horse.isWithdrawn()) {
            throw new ApiException(HttpStatus.CONFLICT, horse.horseName() + " is still at the club");
        }
        if (HorsePolicy.PEDIGREE.equals(HorsePolicy.normalizeType(horse.recordType()))) {
            throw new ApiException(HttpStatus.CONFLICT, "A pedigree record is not kept at the club");
        }
        if (!horses.isActiveHorseOwner(horse.ownerId())) {
            throw new ApiException(HttpStatus.CONFLICT, "The owner's account is not active. Transfer the horse "
                    + "or re-activate the owner first");
        }
        String stall = rawStall == null || rawStall.isBlank() ? null : rawStall.trim();
        if (stall == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A club horse needs a stall", Map.of("stallNo", "Assign a stall"));
        }
        if (horses.existsByStallNo(stall, horseId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Another horse is already in this stall",
                    Map.of("stallNo", "Another horse is already in this stall"));
        }
        String age = HorsePolicy.ageProblem(horse.recordType(), horse.dateOfBirth());
        if (age != null) {
            throw new ApiException(HttpStatus.CONFLICT, age);
        }
        try {
            if (horses.readmit(horseId, stall) == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "This horse has just been changed by someone else");
            }
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "The stall has just been taken by someone else");
        }
        users.writeAudit(actor.userId(), "HORSE_READMIT", "HORSE", horseId, "SUCCESS", ip, "stall " + stall);
        notifications.notifyUser(horse.ownerId(), new Notice("HORSE_READMITTED", "Horse back at the club",
                horse.horseName() + " is back at the club, stall " + stall + ".", Notice.HORSE, horseId));
        return get(horseId);
    }

    private HorseResponse get(int horseId) {
        return horses.findById(horseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Horse not found: #" + horseId));
    }
}
