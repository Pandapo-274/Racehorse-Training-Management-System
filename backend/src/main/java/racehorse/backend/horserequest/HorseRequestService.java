package racehorse.backend.horserequest;

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
import racehorse.backend.horse.HorseResponse;
import racehorse.backend.horse.HorseService;
import racehorse.backend.notification.Notice;
import racehorse.backend.notification.NotificationService;

@Service
public class HorseRequestService {

    private final HorseRequestRepository requests;
    private final HorseService horseService;
    private final UserRepository users;
    private final NotificationService notifications;

    public HorseRequestService(HorseRequestRepository requests, HorseService horseService,
                               UserRepository users, NotificationService notifications) {
        this.requests = requests;
        this.horseService = horseService;
        this.users = users;
        this.notifications = notifications;
    }

    /* ------------------------------------------------------------------ *
     * Chủ ngựa gửi yêu cầu
     * ------------------------------------------------------------------ */

    public HorseRequestRow submit(SubmitRequest raw, AuthenticatedUser me, String ip) {
        String name = raw.horseName().trim();

        if (requests.hasOpenRequest(me.userId(), name)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "You already have a pending request for a horse with this name",
                    Map.of("horseName", "A request for this name is already waiting"));
        }

        String type = HorsePolicy.normalizeType(raw.recordType());

        String ageProblem = HorsePolicy.ageProblem(type, raw.dateOfBirth());
        if (ageProblem != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ageProblem,
                    Map.of("dateOfBirth", ageProblem));
        }

        // The same rules as on the horse itself: right sex, born 2+ years before,
        // and a pedigree record or one of this owner's own horses.
        horseService.checkDeclaredParents(raw.sireId(), raw.damId(), raw.dateOfBirth(), me.userId());

        SubmitRequest clean = new SubmitRequest(
                name, blankToNull(raw.breed()), blankToNull(raw.horseGender()),
                raw.dateOfBirth(), blankToNull(raw.color()), blankToNull(raw.note()), type,
                raw.sireId(), raw.damId());

        int id = requests.insert(me.userId(), clean);
        users.writeAudit(me.userId(), "HORSE_REQUEST_SUBMIT", "HORSE_REQUEST", id, "SUCCESS", ip);

        HorseRequestRow saved = getOrThrow(id);
        notifications.notifyRole("CLUB_MANAGER", me, new Notice(
                "HORSE_REQUEST_SUBMITTED",
                "New horse registration request",
                saved.ownerName() + " asked to register " + saved.horseName() + ".",
                Notice.HORSE_REQUEST, id));
        return saved;
    }

    @Transactional
    public HorseRequestRow cancel(int requestId, AuthenticatedUser me, String ip) {
        HorseRequestRow req = getOrThrow(requestId);
        if (req.ownerId() != me.userId()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This request belongs to another owner");
        }
        requirePending(req);
        if (requests.markCancelled(requestId, me.userId()) == 0) {
            throw alreadyHandled();
        }
        users.writeAudit(me.userId(), "HORSE_REQUEST_CANCEL", "HORSE_REQUEST", requestId, "SUCCESS", ip);
        return getOrThrow(requestId);
    }

    /* ------------------------------------------------------------------ *
     * Xem
     * ------------------------------------------------------------------ */

    public List<HorseRequestRow> list(AuthenticatedUser me) {
        if ("HORSE_OWNER".equals(me.role())) {
            return requests.findByOwner(me.userId());
        }
        return requests.findAll();
    }

    /* ------------------------------------------------------------------ *
     * Học viện xử lý
     * ------------------------------------------------------------------ */

    @Transactional
    public HorseRequestRow approve(int requestId, ReviewRequest review,
                                   AuthenticatedUser me, String ip) {
        HorseRequestRow req = getOrThrow(requestId);
        requirePending(req);

        String code = review == null ? null : blankToNull(review.registrationCode());
        if (code == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "A registration code is required to approve",
                    Map.of("registrationCode", "Enter the code the club assigns"));
        }

        boolean pedigreeOnly = HorsePolicy.PEDIGREE.equals(req.recordType());
        String stall = pedigreeOnly ? null : blankToNull(review.stallNo());
        if (!pedigreeOnly && stall == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "A stall is required to approve a club horse",
                    Map.of("stallNo", "Assign a stall"));
        }

        racehorse.backend.horse.HorseRequest newHorse = new racehorse.backend.horse.HorseRequest(
                req.ownerId(),
                req.sireId(), req.damId(),   // V41: parents from the papers, checked again by create()
                req.horseName(),
                code.toUpperCase(Locale.ROOT),
                req.breed(),
                req.horseGender(),
                req.dateOfBirth(),
                req.color(),
                null,
                stall,
                req.recordType());

        HorseResponse created = horseService.create(newHorse, me, ip);

        // V37: Nếu Club Manager chọn Head Trainer lúc duyệt ngựa ACADEMY thì gán luôn.
        // HorseService.assignTrainer tự gửi thông báo cho Head Trainer và chủ ngựa.
        if (!pedigreeOnly && review.trainerId() != null) {
            created = horseService.assignTrainer(created.horseId(), review.trainerId(), me, ip);
        }

        int changed = requests.markReviewed(requestId, "APPROVED", me.userId(),
                created.horseId(), blankToNull(review.note()));
        if (changed == 0) {
            throw alreadyHandled();
        }

        users.writeAudit(me.userId(), "HORSE_REQUEST_APPROVE", "HORSE_REQUEST",
                requestId, "SUCCESS", ip, "Registration code " + created.registrationCode()
                        + (created.stallNo() == null ? "" : ", stall " + created.stallNo())
                        + (created.trainerName() == null ? "" : ", Head Trainer " + created.trainerName())
                        + (req.sireName() == null ? "" : ", sire " + req.sireName())
                        + (req.damName() == null ? "" : ", dam " + req.damName()));

        notifications.notifyUser(req.ownerId(), new Notice(
                "HORSE_REQUEST_APPROVED",
                "Horse registration approved",
                req.horseName() + " is now registered with the club.",
                Notice.HORSE, created.horseId()));
        return getOrThrow(requestId);
    }

    @Transactional
    public HorseRequestRow reject(int requestId, ReviewRequest review,
                                  AuthenticatedUser me, String ip) {
        HorseRequestRow req = getOrThrow(requestId);
        requirePending(req);

        String note = review == null ? null : blankToNull(review.note());
        if (note == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Say why the request is being turned down",
                    Map.of("note", "A reason is required when rejecting"));
        }

        int changed = requests.markReviewed(requestId, "REJECTED", me.userId(), null, note);
        if (changed == 0) {
            throw alreadyHandled();
        }

        users.writeAudit(me.userId(), "HORSE_REQUEST_REJECT", "HORSE_REQUEST",
                requestId, "SUCCESS", ip, "Reason: " + note);

        notifications.notifyUser(req.ownerId(), new Notice(
                "HORSE_REQUEST_REJECTED",
                "Horse registration declined",
                req.horseName() + ": " + note,
                Notice.HORSE_REQUEST, requestId));
        return getOrThrow(requestId);
    }

    /* ------------------------------------------------------------------ *
     * helper
     * ------------------------------------------------------------------ */

    private HorseRequestRow getOrThrow(int requestId) {
        return requests.findById(requestId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Request #" + requestId + " does not exist"));
    }

    private void requirePending(HorseRequestRow req) {
        if (!"PENDING".equals(req.status())) {
            throw alreadyHandled();
        }
    }

    private ApiException alreadyHandled() {
        return new ApiException(HttpStatus.CONFLICT,
                "This request has already been handled by someone else");
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
