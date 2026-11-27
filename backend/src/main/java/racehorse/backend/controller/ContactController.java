package racehorse.backend.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import racehorse.backend.dto.OwnerContactRequest;
import racehorse.backend.repository.ContactRepository;
import racehorse.backend.security.AuthInterceptor;
import racehorse.backend.security.AuthenticatedUser;
import racehorse.backend.security.RequireRole;
import racehorse.backend.service.ContactService;

/** V63: insurers and owners' people for contract clause 8, picked from lists. */
@RestController
public class ContactController {

    private final ContactService service;

    public ContactController(ContactService service) {
        this.service = service;
    }

    @GetMapping("/api/insurers")
    @RequireRole({"HORSE_OWNER", "CLUB_MANAGER"})
    public List<ContactRepository.Insurer> insurers() {
        return service.insurers();
    }

    /** The owner's own people; the manager passes ownerId (the buyer in a transfer). */
    @GetMapping("/api/owner-contacts")
    @RequireRole({"HORSE_OWNER", "CLUB_MANAGER"})
    public List<ContactRepository.Person> people(
            @RequestParam(required = false) Integer ownerId,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.people(ownerId, user);
    }

    @PostMapping("/api/owner-contacts")
    @RequireRole({"HORSE_OWNER", "CLUB_MANAGER"})
    public ResponseEntity<ContactRepository.Person> add(
            @Valid @RequestBody OwnerContactRequest request,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addPerson(request, user, http.getRemoteAddr()));
    }
}
