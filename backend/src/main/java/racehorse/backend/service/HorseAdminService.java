package racehorse.backend.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import racehorse.backend.dto.HorseResponse;
import racehorse.backend.entity.ContractRow;
import racehorse.backend.entity.Notice;
import racehorse.backend.exception.ApiException;
import racehorse.backend.repository.HorseRepository;
import racehorse.backend.repository.UserRepository;
import racehorse.backend.security.AuthenticatedUser;

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
    private final ContractService contracts;
    private final racehorse.backend.repository.ContactRepository contactBook;

    public HorseAdminService(HorseRepository horses, UserRepository users, NotificationService notifications,
                             ContractService contracts, racehorse.backend.repository.ContactRepository contactBook) {
        this.horses = horses;
        this.users = users;
        this.notifications = notifications;
        this.contracts = contracts;
        this.contactBook = contactBook;
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
     *
     * V61: the boarding contract does not move with the horse. The seller's
     * contract ends and stays in the seller's account; the buyer gets a contract
     * for the rest of the same stay (nothing to pay) with the buyer's own insurer
     * and second contact, which the manager must give here. A stay that ended,
     * or a renewal still open, must be settled first.
     */
    @Transactional
    public HorseResponse transferOwner(int horseId, int newOwnerId, String reason,
                                       ContractService.Contacts newContacts, AuthenticatedUser actor, String ip) {
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
        // V61: what happens to the stay is checked before anything changes.
        ContractRow source = contracts.transferSource(horseId, horse.horseName());
        if (source != null) {
            Map<String, String> missing = new java.util.LinkedHashMap<>(newContacts.problems());
            // V63: picked from the lists - the insurer the club accepts, a person on the buyer's list.
            if (!missing.containsKey("insurerName") && !missing.containsKey("insurerPhone")
                    && !contactBook.isListedInsurer(newContacts.insurerName(), newContacts.insurerPhone())) {
                missing.put("insurerName", "Choose the insurer from the list");
            }
            if (!missing.containsKey("emergencyName") && !missing.containsKey("emergencyPhone")
                    && !contactBook.isOwnersPerson(newOwnerId, newContacts.emergencyName(), newContacts.emergencyPhone())) {
                missing.put("emergencyName", "Choose a person from the new owner's contacts");
            }
            if (!missing.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "The new owner signs a contract for the rest of the stay: give their insurer and second contact",
                        missing);
            }
        }
        String why = reason.trim();
        horses.updateOwner(horseId, newOwnerId);
        HorseResponse after = get(horseId);
        Integer takeoverId = source == null ? null
                : contracts.handOver(source, after, newOwnerId, after.ownerName(), newContacts, actor, ip);
        users.writeAudit(actor.userId(), "HORSE_OWNER_TRANSFER", "HORSE", horseId, "SUCCESS", ip,
                "Owner: " + horse.ownerName() + " → " + after.ownerName() + ". Reason: " + why
                        + (takeoverId == null ? "" : ". Contract #" + source.contractId() + " closed, #"
                                + takeoverId + " sent to the new owner"));
        notifications.notifyUser(horse.ownerId(), new Notice("HORSE_TRANSFERRED", "Horse transferred",
                horse.horseName() + " now belongs to " + after.ownerName() + ". Reason: " + why
                        + (source == null ? ""
                                : ". Your contract for its stay is closed and stays under Contracts."),
                null, null));
        if (takeoverId == null) {
            // With a stay, handOver already asked the new owner to sign.
            notifications.notifyUser(newOwnerId, new Notice("HORSE_TRANSFERRED", "Horse transferred to you",
                    horse.horseName() + " (" + horse.registrationCode() + ") is now registered in your name.",
                    Notice.HORSE, horseId));
        }
        return after;
    }

    /**
     * A withdrawn club horse comes back: ACTIVE again with a stall. The health
     * status is kept as the vet last left it; plans and sessions start fresh
     * (withdrawal ended them). The old HORSE_WITHDRAWAL row stays as history.
     */
    @Transactional
    public HorseResponse readmit(int horseId, String rawStall, AuthenticatedUser actor, String ip) {
        // Thứ tự kiểm: đang rút? -> không phải PEDIGREE -> chủ còn ACTIVE -> có chuồng trống -> tuổi
        // còn dưới CLUB_MAX_AGE. Câu UPDATE còn kèm "WHERE activity_status = 'WITHDRAWN'" để hai người
        // bấm cùng lúc thì chỉ một người thành công (readmit trả 0 cho người còn lại).
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
        if (horses.isStallOffPlan(stall)) {
            String offPlan = HorsePolicy.stallOffPlan(stall);
            throw new ApiException(HttpStatus.BAD_REQUEST, offPlan, Map.of("stallNo", offPlan));
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
        users.writeAudit(actor.userId(), "HORSE_READMIT", "HORSE", horseId, "SUCCESS", ip, "Back at the club in stall " + stall);
        notifications.notifyUser(horse.ownerId(), new Notice("HORSE_READMITTED", "Horse back at the club",
                horse.horseName() + " is back at the club, stall " + stall + ".", Notice.HORSE, horseId));
        return get(horseId);
    }

    private HorseResponse get(int horseId) {
        return horses.findById(horseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Horse not found: #" + horseId));
    }
}
