package racehorse.backend.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import racehorse.backend.dto.OwnerContactRequest;
import racehorse.backend.exception.ApiException;
import racehorse.backend.repository.ContactRepository;
import racehorse.backend.repository.HorseRepository;
import racehorse.backend.repository.UserRepository;
import racehorse.backend.security.AuthenticatedUser;

/**
 * V63: insurer and second contact are picked from lists (contract clause 8).
 * An owner keeps their own people; the Club Manager may add one for an owner during a
 * transfer (the buyer gives it at the sale). Nobody sees another owner's people.
 */
@Service
public class ContactService {

    private final ContactRepository contacts;
    private final HorseRepository horses;
    private final UserRepository users;

    public ContactService(ContactRepository contacts, HorseRepository horses, UserRepository users) {
        this.contacts = contacts;
        this.horses = horses;
        this.users = users;
    }

    public List<ContactRepository.Insurer> insurers() {
        return contacts.listInsurers();
    }

    public List<ContactRepository.Person> people(Integer ownerId, AuthenticatedUser me) {
        return contacts.listPeople(ownerOf(ownerId, me));
    }

    public ContactRepository.Person addPerson(OwnerContactRequest r, AuthenticatedUser me, String ip) {
        int owner = ownerOf(r.ownerId(), me);
        String name = r.fullName().trim().replaceAll("\\s+", " ");
        String phone = r.phone().trim();
        String relationship = r.relationship() == null || r.relationship().isBlank() ? null : r.relationship().trim();
        var existing = contacts.findPerson(owner, name, phone);
        if (existing.isPresent()) {
            return existing.get();
        }
        int id;
        try {
            id = contacts.addPerson(owner, name, phone, relationship);
        } catch (DataIntegrityViolationException e) {
            // UX_OCON_person: added a moment ago in another tab
            return contacts.findPerson(owner, name, phone).orElseThrow(() -> e);
        }
        users.writeAudit(me.userId(), "OWNER_CONTACT_ADD", "APP_USER", owner, "SUCCESS", ip, name);
        return new ContactRepository.Person(id, owner, name, phone, relationship);
    }

    /**
     * Problems with the clause 8 contacts given for an owner: they must come from the lists.
     * Empty when all four are fine. Same field names as the registration form.
     */
    public Map<String, String> clause8Problems(int ownerId, String insurerName, String insurerPhone,
                                               String emergencyName, String emergencyPhone) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (insurerName == null || insurerPhone == null) {
            errors.put("insurerName", "Choose the insurer from the list");
        } else if (!contacts.isListedInsurer(insurerName, insurerPhone)) {
            errors.put("insurerName", "Choose the insurer from the list");
        }
        if (emergencyName == null || emergencyPhone == null) {
            errors.put("emergencyName", "Choose a second person to call");
        } else if (!contacts.isOwnersPerson(ownerId, emergencyName, emergencyPhone)) {
            errors.put("emergencyName", "Choose a person from the owner's contacts");
        }
        return errors;
    }

    /** An owner manages their own list; the manager names the owner (a transfer). */
    private int ownerOf(Integer ownerId, AuthenticatedUser me) {
        if ("HORSE_OWNER".equals(me.role())) {
            return me.userId();
        }
        if (ownerId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Choose the owner", Map.of("ownerId", "Choose the owner"));
        }
        if (!horses.isActiveHorseOwner(ownerId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Not an active Horse Owner",
                    Map.of("ownerId", "Choose an active Horse Owner"));
        }
        return ownerId;
    }
}
