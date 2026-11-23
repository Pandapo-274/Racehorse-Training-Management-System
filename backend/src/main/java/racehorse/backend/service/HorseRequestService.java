package racehorse.backend.service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import racehorse.backend.dto.HorseRequest;
import racehorse.backend.dto.HorseResponse;
import racehorse.backend.dto.ReviewRequest;
import racehorse.backend.dto.SubmitRequest;
import racehorse.backend.entity.HorseRequestRow;
import racehorse.backend.entity.Notice;
import racehorse.backend.exception.ApiException;
import racehorse.backend.repository.HorseRequestRepository;
import racehorse.backend.repository.UserRepository;
import racehorse.backend.security.AuthenticatedUser;

/*
 * Nghiệp vụ yêu cầu đăng ký ngựa (V5). Luật chính:
 *   - Một chủ không có hai yêu cầu PENDING trùng tên ngựa (kiểm ở đây + UX_HREQ_open trong DB).
 *   - Tuổi phải hợp lệ cho ngựa ACADEMY (HorsePolicy.ageProblem), cha mẹ khai phải đúng luật phả hệ.
 *   - V48 (góp ý review): ngựa ACADEMY phải khai cân nặng, chiều cao, thời gian huấn luyện (>= 3 tuần)
 *     và thời hạn gửi (>= 1 tháng); phải có ảnh thì Manager mới duyệt được.
 *   - V49: DUYỆT ngựa ACADEMY không tạo ngựa nữa mà gửi hợp đồng (ContractService). Mã đăng ký,
 *     chuồng, HLV chốt lúc NHẬN ngựa, sau khi chủ ký và trả tiền. Hồ sơ PEDIGREE không ở CLB nên
 *     vẫn duyệt như cũ: cấp mã, tạo hồ sơ ngay.
 *   - Mọi bước "đổi trạng thái" dùng UPDATE ... WHERE status = 'PENDING' để hai người bấm cùng lúc
 *     không cùng thắng (người thua nhận 409 "already handled").
 */
@Service
public class HorseRequestService {

    private final HorseRequestRepository requests;
    private final HorseService horseService;
    private final UserRepository users;
    private final NotificationService notifications;
    private final ContractService contracts;

    public HorseRequestService(HorseRequestRepository requests, HorseService horseService,
                               UserRepository users, NotificationService notifications,
                               ContractService contracts) {
        this.requests = requests;
        this.horseService = horseService;
        this.users = users;
        this.notifications = notifications;
        this.contracts = contracts;
    }

    /* ------------------------------------------------------------------ *
     * Chủ ngựa gửi yêu cầu
     * ------------------------------------------------------------------ */

    // Không có @Transactional: INSERT, audit và thông báo mỗi câu tự commit riêng. Nếu một bước sau
    // lỗi thì yêu cầu vẫn đã được lưu (không gây sai dữ liệu nghiêm trọng vì chỉ là lời đề nghị).
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
        boolean club = HorsePolicy.ACADEMY.equals(type);
        if (club) {
            checkClubHorseFields(raw);
        }
        horseService.checkDeclaredParents(raw.sireId(), raw.damId(), raw.dateOfBirth(), me.userId());

        // A pedigree record never stays at the club: its stay fields are dropped, not stored.
        // training_weeks is derived from the window, never taken from the client.
        SubmitRequest clean = new SubmitRequest(
                name, blankToNull(raw.breed()), blankToNull(raw.horseGender()),
                raw.dateOfBirth(), blankToNull(raw.color()), blankToNull(raw.note()), type,
                raw.sireId(), raw.damId(),
                club ? raw.weight() : null, club ? raw.heightCm() : null,
                club ? blankToNull(raw.surface()) : null, club ? blankToNull(raw.distancePref()) : null,
                club ? blankToNull(raw.runningStyle()) : null,
                club ? raw.preferredStart() : null,
                club ? HorsePolicy.trainingWeeks(raw.trainingStart(), raw.trainingEnd()) : null,
                club ? raw.contractMonths() : null, club ? raw.preferredTrainerId() : null,
                club ? raw.trainingStart() : null, club ? raw.trainingEnd() : null);
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

    /** What a club horse must carry for the manager to have a basis to approve it. */
    private void checkClubHorseFields(SubmitRequest raw) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (raw.weight() == null) errors.put("weight", "Weight is required for a club horse");
        if (raw.heightCm() == null) errors.put("heightCm", "Height is required for a club horse");
        // V50: arrival day, training window (>= 3 weeks, inside the stay) and length of stay.
        errors.putAll(HorsePolicy.stayProblems(raw.preferredStart(), raw.trainingStart(), raw.trainingEnd(),
                raw.contractMonths(), LocalDate.now()));
        if (raw.preferredTrainerId() != null
                && horseService.listAvailableTrainers().stream()
                        .noneMatch(t -> t.userId() == raw.preferredTrainerId())) {
            errors.put("preferredTrainerId", "This Head Trainer cannot take another horse. Pick another or leave it to the club");
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Some details are missing", errors);
        }
    }

    /** Head Trainers an owner can ask for: active and below their limit. Names only. */
    public List<Map<String, Object>> trainerOptions() {
        return horseService.listAvailableTrainers().stream()
                .map(t -> Map.<String, Object>of("userId", t.userId(), "fullName", t.fullName()))
                .toList();
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
        // Cả approve chạy trong MỘT giao dịch: tạo HORSE, gán HLV, đánh dấu APPROVED, audit, thông
        // báo. Nếu markReviewed trả 0 (ai đó vừa xử lý) thì ném lỗi -> rollback luôn con ngựa vừa tạo.
        HorseRequestRow req = getOrThrow(requestId);
        requirePending(req);
        boolean pedigreeOnly = HorsePolicy.PEDIGREE.equals(req.recordType());
        return pedigreeOnly
                ? approvePedigree(req, review, me, ip)
                : approveClubHorse(req, review, me, ip);
    }

    /**
     * V49: a club horse is approved by sending the owner a contract. The horse itself is
     * created when it arrives (ContractService.receive), after the owner signed and paid.
     */
    private HorseRequestRow approveClubHorse(HorseRequestRow req, ReviewRequest review,
                                             AuthenticatedUser me, String ip) {
        Map<String, String> errors = new LinkedHashMap<>();
        // Review feedback 1: no approval without a photo.
        if (req.photoUrl() == null) {
            errors.put("photo", "The owner has not added a photo yet. Ask for one before approving");
        }
        // Review feedback 2: the manager confirms the two things only a person can check.
        if (review == null || !Boolean.TRUE.equals(review.photoChecked())) {
            errors.put("photoChecked", "Confirm the photo matches the description");
        }
        if (review == null || !Boolean.TRUE.equals(review.papersChecked())) {
            errors.put("papersChecked", "Confirm you checked the parents against the papers");
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "This request cannot be approved yet", errors);
        }
        String note = review.note() == null ? null : blankToNull(review.note());
        int changed = requests.markReviewed(req.requestId(), "APPROVED", me.userId(), null, note);
        if (changed == 0) {
            throw alreadyHandled();
        }
        int contractId = contracts.offerForRequest(req, me);
        users.writeAudit(me.userId(), "HORSE_REQUEST_APPROVE", "HORSE_REQUEST",
                req.requestId(), "SUCCESS", ip,
                "Photo matches description: yes. Parents checked against papers: yes. "
                        + "Contract #" + contractId + " sent, " + req.contractMonths() + " month(s)"
                        + (req.growth() == null ? "" : ". Growth for age: " + req.growth()));
        notifications.notifyUser(req.ownerId(), new Notice(
                "HORSE_REQUEST_APPROVED",
                "Registration approved - sign the contract",
                req.horseName() + " was approved. Read, sign and pay the contract to bring the horse in.",
                Notice.HORSE_REQUEST, req.requestId()));
        return getOrThrow(req.requestId());
    }

    /** A pedigree-only record has no stay and no contract: the code is issued straight away. */
    private HorseRequestRow approvePedigree(HorseRequestRow req, ReviewRequest review,
                                            AuthenticatedUser me, String ip) {
        String code = review == null ? null : blankToNull(review.registrationCode());
        if (code == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "A registration code is required to approve",
                    Map.of("registrationCode", "Enter the code the club assigns"));
        }

        // Dựng HorseRequest (DTO nội bộ) từ dữ liệu chủ khai + mã Manager cấp. Hồ sơ pedigree không ở
        // CLB nên không có cân nặng, không chuồng. Tên đầy đủ racehorse.backend.dto.HorseRequest để khỏi
        // nhầm với chữ "request" của yêu cầu đăng ký.
        racehorse.backend.dto.HorseRequest newHorse = new racehorse.backend.dto.HorseRequest(
                req.ownerId(),
                req.sireId(), req.damId(),   // V41: parents from the papers, checked again by create()
                req.horseName(),
                code.toUpperCase(Locale.ROOT),
                req.breed(),
                req.horseGender(),
                req.dateOfBirth(),
                req.color(),
                null,
                null,
                req.recordType());
        HorseResponse created = horseService.create(newHorse, me, ip);
        int changed = requests.markReviewed(req.requestId(), "APPROVED", me.userId(),
                created.horseId(), blankToNull(review.note()));
        if (changed == 0) {
            throw alreadyHandled();
        }
        users.writeAudit(me.userId(), "HORSE_REQUEST_APPROVE", "HORSE_REQUEST",
                req.requestId(), "SUCCESS", ip, "Registration code " + created.registrationCode()
                        + " (pedigree record)"
                        + (req.sireName() == null ? "" : ", sire " + req.sireName())
                        + (req.damName() == null ? "" : ", dam " + req.damName()));
        notifications.notifyUser(req.ownerId(), new Notice(
                "HORSE_REQUEST_APPROVED",
                "Horse registration approved",
                req.horseName() + " is now on the club's pedigree records.",
                Notice.HORSE, created.horseId()));
        return getOrThrow(req.requestId());
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
