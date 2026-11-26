package racehorse.backend.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import racehorse.backend.dto.HorseIdentityRequest;
import racehorse.backend.dto.HorseRequest;
import racehorse.backend.dto.HorseResponse;
import racehorse.backend.dto.HorseUpdateRequest;
import racehorse.backend.dto.HorseVitalsResponse;
import racehorse.backend.dto.PedigreeNode;
import racehorse.backend.entity.Notice;
import racehorse.backend.exception.ApiException;
import racehorse.backend.repository.HorseRepository;
import racehorse.backend.repository.HorseVitalsRepository;
import racehorse.backend.repository.UserRepository;
import racehorse.backend.security.AuthenticatedUser;

/*
 * Nghiệp vụ hồ sơ ngựa (Flow 1). Các luật chính:
 *   - Ai xem được ngựa nào: checkCanView (chủ ngựa chỉ ngựa của mình), list() lọc theo vai trò.
 *   - Chủ ngựa chỉ sửa tên, giống, giới tính, ngày sinh, màu và điền cha/mẹ còn trống
 *     (updateByOwner). Giới tính và ngày sinh bị KHOÁ khi ngựa đã có con hoặc đã có buổi tập.
 *   - Phả hệ hợp lệ (checkParent): cha phải là COLT/STALLION, mẹ là FILLY/MARE, sinh trước con ít
 *     nhất FOAL_BELOW (2) năm, không phải chính nó, không tạo vòng lặp tổ tiên.
 *   - Club Manager sửa mã đăng ký, chuồng, HLV phụ trách (updateIdentity) và sửa cha mẹ đã có
 *     (correctParents, bắt buộc lý do).
 *   - HLV phụ trách không vượt hạn mức max_horses (validateTrainerLimit).
 */
@Service
public class HorseService {

    // GELDING (ngựa thiến) không thể làm cha nên không có trong SIRE_GENDERS. Frontend có bản sao
    // SIRE_GENDERS / DAM_GENDERS trong horseApi.js để lọc danh sách chọn.
    private static final Set<String> SIRE_GENDERS = Set.of("COLT", "STALLION");
    private static final Set<String> DAM_GENDERS = Set.of("FILLY", "MARE");

    private final HorseRepository horseRepository;
    private final HorseVitalsRepository vitalsRepository;
    private final UserRepository userRepository;
    private final NotificationService notifications;

    // Constructor 3 tham số: dùng trong unit test (BusinessRulesTest) không cần NotificationService.
    // Khi đó notifications = null nên các chỗ gửi thông báo đều kiểm "if (notifications == null)".
    public HorseService(HorseRepository horseRepository, HorseVitalsRepository vitalsRepository,
                        UserRepository userRepository) {
        this(horseRepository, vitalsRepository, userRepository, null);
    }

    // @Autowired chỉ rõ cho Spring dùng constructor này khi class có nhiều constructor.
    @Autowired
    public HorseService(HorseRepository horseRepository, HorseVitalsRepository vitalsRepository,
                        UserRepository userRepository, NotificationService notifications) {
        this.horseRepository = horseRepository;
        this.vitalsRepository = vitalsRepository;
        this.userRepository = userRepository;
        this.notifications = notifications;
    }

    @Transactional
    public HorseResponse create(HorseRequest raw, AuthenticatedUser user, String ip) {
        // Chỉ được gọi nội bộ từ HorseRequestService.approve (không còn POST /api/horses).
        HorseRequest request = normalize(raw);
        validate(request, 0);
        validateTypeRules(request);
        int id;
        try {
            id = horseRepository.insert(request);
        } catch (DataIntegrityViolationException e) {
            // The check in validate() passed, then the INSERT hit UNIQUE: someone else took the
            // code or the stall between the two (two managers receiving at once). Ask again which
            // one, so the error lands on the right field instead of a generic 409.
            throw takenJustNow(request.registrationCode(), request.stallNo(), 0);
        }
        userRepository.writeAudit(user.userId(), "HORSE_CREATE", "HORSE", id, "SUCCESS", ip);
        return getOrThrow(id);
    }

    /**
     * Club Manager sửa mã đăng ký, số chuồng và điều phối Head Trainer phụ trách (V37).
     */
    @Transactional
    public HorseResponse updateIdentity(int horseId, HorseIdentityRequest raw, AuthenticatedUser user, String ip) {
        if (user != null && !"CLUB_MANAGER".equals(user.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only Club Manager can update horse identity and assigned trainer");
        }
        HorseResponse current = getOrThrow(horseId);
        String code = raw.registrationCode().trim().toUpperCase(java.util.Locale.ROOT);
        String stall = blankToNull(raw.stallNo());
        Integer trainerId = raw.trainerId();

        Map<String, String> errors = new HashMap<>();
        // Format only for a NEW code: older records keep whatever shape they were given.
        String format = code.equals(current.registrationCode()) ? null : HorsePolicy.codeProblem(code);
        if (format != null) {
            errors.put("registrationCode", format);
        } else if (horseRepository.existsByRegistrationCode(code, horseId)) {
            errors.put("registrationCode", "This registration code is already in use");
        }
        // Hồ sơ PEDIGREE và ngựa đã rút không có chuồng, không có HLV; ngựa CLB đang ở thì bắt buộc có chuồng.
        boolean pedigree = HorsePolicy.PEDIGREE.equals(HorsePolicy.normalizeType(current.recordType()));
        if (stall != null && (pedigree || current.isWithdrawn())) {
            errors.put("stallNo", pedigree ? "A pedigree record has no stall" : "A withdrawn horse has no stall");
        } else if (stall == null && !pedigree && !current.isWithdrawn()) {
            errors.put("stallNo", "A club horse needs a stall");
        } else if (stall != null && horseRepository.existsByStallNo(stall, horseId)) {
            errors.put("stallNo", "Another horse is already in this stall");
        }

        if (trainerId != null && (pedigree || current.isWithdrawn())) {
            errors.put("trainerId", pedigree
                    ? "A pedigree record cannot be assigned to a Head Trainer"
                    : "A withdrawn horse cannot be assigned to a Head Trainer");
        } else if (trainerId != null) {
            validateTrainerLimit(trainerId, horseId, errors);
        }

        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid data", errors);
        }
        try {
            horseRepository.updateIdentity(horseId, code, stall);
            horseRepository.updateTrainer(horseId, (pedigree || current.isWithdrawn()) ? null : trainerId);
        } catch (DataIntegrityViolationException e) {
            throw takenJustNow(code, stall, horseId);
        }
        HorseResponse updated = getOrThrow(horseId);
        userRepository.writeAudit(user.userId(), "HORSE_IDENTITY_UPDATE", "HORSE", horseId, "SUCCESS", ip,
                identityChanges(current, updated));
        notifyTrainerChange(current, updated);
        return updated;
    }

    /** V37: Gán Head Trainer cho ngựa lúc duyệt hồ sơ (HorseRequestService.approve). */
    @Transactional
    public HorseResponse assignTrainer(int horseId, Integer trainerId, AuthenticatedUser user, String ip) {
        if (user != null && !"CLUB_MANAGER".equals(user.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only Club Manager can assign horses to Head Trainers");
        }
        HorseResponse current = getOrThrow(horseId);
        boolean pedigree = HorsePolicy.PEDIGREE.equals(HorsePolicy.normalizeType(current.recordType()));
        if (trainerId != null && (pedigree || current.isWithdrawn())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Cannot assign a Head Trainer to a pedigree or withdrawn horse");
        }
        if (trainerId != null) {
            Map<String, String> errors = new HashMap<>();
            validateTrainerLimit(trainerId, horseId, errors);
            if (!errors.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, errors.get("trainerId"), errors);
            }
        }
        horseRepository.updateTrainer(horseId, trainerId);
        HorseResponse updated = getOrThrow(horseId);
        userRepository.writeAudit(user.userId(), "HORSE_ASSIGN_TRAINER:" + (trainerId == null ? "NONE" : trainerId),
                "HORSE", horseId, "SUCCESS", ip,
                "Head Trainer: " + orNone(current.trainerName()) + " → " + orNone(updated.trainerName()));
        notifyTrainerChange(current, updated);
        return updated;
    }

    /**
     * V37: Khi Club Manager giao / đổi / gỡ Head Trainer phụ trách ngựa, báo cho
     * Head Trainer mới (được giao ngựa nào), Head Trainer cũ (không còn quản lý)
     * và chủ ngựa (ngựa của mình được giao cho Head Trainer nào).
     */
    private void notifyTrainerChange(HorseResponse before, HorseResponse after) {
        if (notifications == null) return;
        Integer oldId = before.trainerId();
        Integer newId = after.trainerId();
        if (java.util.Objects.equals(oldId, newId)) return;
        String horseLabel = after.horseName() + " (" + after.registrationCode() + ")";

        if (newId != null) {
            notifications.notifyUser(newId, new Notice(
                    "HORSE_ASSIGNED_TO_TRAINER",
                    "New horse assigned to your management",
                    "The club manager assigned " + horseLabel + " to your training management.",
                    Notice.HORSE, after.horseId()));
        }
        if (oldId != null) {
            notifications.notifyUser(oldId, new Notice(
                    "HORSE_UNASSIGNED_FROM_TRAINER",
                    "Horse removed from your management",
                    "The club manager moved " + horseLabel + " out of your training management"
                            + (after.trainerName() != null ? " to Head Trainer " + after.trainerName() : "") + ".",
                    Notice.HORSE, after.horseId()));
        }
        notifications.notifyUser(after.ownerId(), new Notice(
                "HORSE_TRAINER_CHANGED",
                newId != null ? "Head trainer assigned to your horse" : "Head trainer removed from your horse",
                newId != null
                        ? "Your horse " + horseLabel + " is now managed by Head Trainer " + after.trainerName() + "."
                        : "Your horse " + horseLabel + " no longer has a Head Trainer assigned.",
                Notice.HORSE, after.horseId()));
    }

    public List<HorseRepository.TrainerSummary> listActiveTrainers() {
        return horseRepository.listActiveTrainers();
    }

    /**
     * Review feedback: a Head Trainer who is already full is not a choice, so do not show
     * them at all (before, the dropdown showed them greyed out). Same list for the manager's
     * dropdowns and for the owner's "preferred trainer".
     */
    public List<HorseRepository.TrainerSummary> listAvailableTrainers() {
        return horseRepository.listActiveTrainers().stream()
                .filter(t -> t.managedHorseCount() < t.maxHorses())
                .toList();
    }

    /* ------------------------------------------------------------------
     * Registration code helpers (review feedback: "how do you know it is unique?")
     * The answer stays the UNIQUE constraint; these let the manager see a clash
     * while typing instead of after pressing the button.
     * ------------------------------------------------------------------ */

    public static final String CODE_PREFIX = "TM";

    /** Next free code in the seed format, e.g. TM-0751. The manager can still change it. */
    public String suggestRegistrationCode() {
        int next = horseRepository.maxCodeNumber(CODE_PREFIX) + 1;
        String code = String.format("%s-%04d", CODE_PREFIX, next);
        // Paranoia: a hand-typed code may already sit on that number with a suffix.
        while (horseRepository.existsByRegistrationCode(code, 0)) {
            next++;
            code = String.format("%s-%04d", CODE_PREFIX, next);
        }
        return code;
    }

    public record CodeCheck(String code, boolean available, String message) {}

    public CodeCheck checkRegistrationCode(String raw, Integer exceptHorseId) {
        String code = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
        String format = HorsePolicy.codeProblem(code);
        if (format != null) {
            return new CodeCheck(code, false, format);
        }
        if (!horseRepository.existsByRegistrationCode(code, exceptHorseId == null ? 0 : exceptHorseId)) {
            return new CodeCheck(code, true, "Available");
        }
        String holder = horseRepository.findNameByRegistrationCode(code).orElse("another horse");
        return new CodeCheck(code, false, "Already used by " + holder);
    }

    private ApiException takenJustNow(String code, String stall, int excludeId) {
        Map<String, String> errors = new HashMap<>();
        if (code != null && horseRepository.existsByRegistrationCode(code, excludeId)) {
            errors.put("registrationCode", "Someone has just used this code. Pick another one");
        }
        if (stall != null && horseRepository.existsByStallNo(stall, excludeId)) {
            errors.put("stallNo", "Another horse has just been put in this stall");
        }
        return new ApiException(HttpStatus.CONFLICT,
                errors.isEmpty() ? "The registration code or stall has just been taken by someone else"
                        : String.join(" ", errors.values()),
                errors);
    }

    private void validateTrainerLimit(int trainerId, int excludeHorseId, Map<String, String> errors) {
        var trainerOpt = horseRepository.findActiveTrainer(trainerId);
        if (trainerOpt.isEmpty()) {
            errors.put("trainerId", "Selected user is not an active Head Trainer");
            return;
        }
        var trainer = trainerOpt.get();
        int currentCount = horseRepository.countManagedHorses(trainerId, excludeHorseId);
        if (currentCount >= trainer.maxHorses()) {
            errors.put("trainerId", "Head Trainer " + trainer.fullName()
                    + " has reached their maximum limit of " + trainer.maxHorses() + " horses");
        }
    }

    /**
     * Chủ ngựa sửa thông tin cơ bản của NGỰA CỦA MÌNH.
     */
    @Transactional
    public HorseResponse updateByOwner(int horseId, HorseUpdateRequest raw,
                                       AuthenticatedUser user, String ip) {
        HorseResponse current = getOrThrow(horseId);
        if (!"HORSE_OWNER".equals(user.role()) || current.ownerId() != user.userId()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only edit your own horses");
        }

        HorseUpdateRequest request = new HorseUpdateRequest(
                raw.horseName().trim(), blankToNull(raw.breed()), raw.horseGender(),
                raw.dateOfBirth(), blankToNull(raw.color()), raw.sireId(), raw.damId());

        Map<String, String> errors = new HashMap<>();
        checkParent(request.sireId(), "sireId", "Sire", SIRE_GENDERS, horseId, request.dateOfBirth(), errors);
        checkParent(request.damId(), "damId", "Dam", DAM_GENDERS, horseId, request.dateOfBirth(), errors);
        // V41: parents are a fact from the horse's papers. One already on record is
        // locked for the owner (the club corrects it, with a reason); one still
        // unknown may be declared, from a pedigree record or the owner's own horses.
        checkParentLocked(current.sireId(), request.sireId(), "sireId", "sire", errors);
        checkParentLocked(current.damId(), request.damId(), "damId", "dam", errors);
        checkParentReachable(request.sireId(), current.sireId(), "sireId", user.userId(), errors);
        checkParentReachable(request.damId(), current.damId(), "damId", user.userId(), errors);

        // Khoá giới tính / ngày sinh khi ngựa đã có con hoặc đã tập: đổi lúc đó sẽ làm sai luật
        // phả hệ của con và số liệu huấn luyện đã ghi.
        boolean pedigreeChanged = !java.util.Objects.equals(current.horseGender(), request.horseGender())
                || !java.util.Objects.equals(current.dateOfBirth(), request.dateOfBirth());
        if (pedigreeChanged) {
            boolean hasOffspring = horseRepository.hasOffspring(horseId);
            boolean hasTrained = vitalsRepository.findLatestSession(horseId).isPresent();
            if (hasOffspring || hasTrained) {
                errors.put("horseGender", "Sex and date of birth are locked because "
                        + current.horseName() + (hasOffspring ? " is already a parent of another horse"
                                                              : " already has training sessions")
                        + ". Ask the club to correct them");
            }
        }
        if (request.sireId() != null && request.sireId().equals(request.damId())) {
            errors.put("damId", "Sire and dam cannot be the same horse");
        }
        String ageProblem = HorsePolicy.ageProblem(current.recordType(), request.dateOfBirth());
        if (ageProblem != null) errors.put("dateOfBirth", ageProblem);

        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid data", errors);
        }

        horseRepository.updateBasics(horseId, request);
        userRepository.writeAudit(user.userId(), "HORSE_UPDATE", "HORSE", horseId, "SUCCESS", ip,
                changes(current, request));
        return getOrThrow(horseId);
    }

    /**
     * V41: parents declared on a registration request get the same checks as on
     * the horse itself: they exist, have the right sex, were born 2+ years before
     * (when the foal's date of birth is known), are not the same horse, and are a
     * pedigree record or one of this owner's own horses.
     */
    public void checkDeclaredParents(Integer sireId, Integer damId, java.time.LocalDate dob, int ownerId) {
        Map<String, String> errors = new HashMap<>();
        checkParent(sireId, "sireId", "Sire", SIRE_GENDERS, 0, dob, errors);
        checkParent(damId, "damId", "Dam", DAM_GENDERS, 0, dob, errors);
        checkParentReachable(sireId, null, "sireId", ownerId, errors);
        checkParentReachable(damId, null, "damId", ownerId, errors);
        if (sireId != null && sireId.equals(damId)) {
            errors.put("damId", "Sire and dam cannot be the same horse");
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid data", errors);
        }
    }

    /**
     * V41: the club manager corrects the parents on record, e.g. after checking
     * the papers. Any horse can be named (a parent may belong to another owner),
     * but the biological checks still apply. A reason is required; it goes into
     * the audit log and to the owner.
     */
    @Transactional
    public HorseResponse correctParents(int horseId, Integer sireId, Integer damId, String reason,
                                        AuthenticatedUser user, String ip) {
        if (user != null && !"CLUB_MANAGER".equals(user.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the club manager can correct a horse's parents");
        }
        HorseResponse current = getOrThrow(horseId);
        String why = blankToNull(reason);

        Map<String, String> errors = new HashMap<>();
        if (why == null) errors.put("reason", "Say why the parents are corrected (for example, what the papers show)");
        checkParent(sireId, "sireId", "Sire", SIRE_GENDERS, horseId, current.dateOfBirth(), errors);
        checkParent(damId, "damId", "Dam", DAM_GENDERS, horseId, current.dateOfBirth(), errors);
        if (sireId != null && sireId.equals(damId)) {
            errors.put("damId", "Sire and dam cannot be the same horse");
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid data", errors);
        }
        if (java.util.Objects.equals(current.sireId(), sireId) && java.util.Objects.equals(current.damId(), damId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "These are already the parents on record");
        }

        horseRepository.updateParents(horseId, sireId, damId);
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (!java.util.Objects.equals(current.sireId(), sireId)) {
            parts.add("Sire: " + parentName(current.sireId()) + " → " + parentName(sireId));
        }
        if (!java.util.Objects.equals(current.damId(), damId)) {
            parts.add("Dam: " + parentName(current.damId()) + " → " + parentName(damId));
        }
        String detail = String.join(". ", parts) + ". Reason: " + why;
        userRepository.writeAudit(user == null ? null : user.userId(), "HORSE_PARENTS_CORRECT", "HORSE", horseId,
                "SUCCESS", ip, detail);
        if (notifications != null) {
            notifications.notifyUser(current.ownerId(), new Notice("HORSE_PARENTS_CORRECTED",
                    "Parents corrected by the club", current.horseName() + ": " + detail, Notice.HORSE, horseId));
        }
        return getOrThrow(horseId);
    }

    private String parentName(Integer id) {
        if (id == null) return "unknown";
        return horseRepository.findById(id).map(HorseResponse::horseName).orElse("#" + id);
    }

    /** V41: a parent already on record cannot be changed or removed by the owner. */
    private static void checkParentLocked(Integer onRecord, Integer requested, String field, String word,
                                          Map<String, String> errors) {
        if (onRecord != null && !onRecord.equals(requested)) {
            errors.put(field, "The " + word + " is already on record from the horse's papers. Ask the club to correct it");
        }
    }

    public HorseResponse get(int horseId, AuthenticatedUser user) {
        HorseResponse horse = getOrThrow(horseId);
        checkCanView(horse, user);
        // Như list(): Groom làm việc trong chuồng, ngựa đã rút không còn ở đó.
        if ("GROOM".equals(user.role()) && horse.isWithdrawn()) {
            throw new ApiException(HttpStatus.FORBIDDEN, horse.horseName() + " has left the club");
        }
        return horse;
    }

    /**
     * Ngựa chọn được làm cha / mẹ trên form sửa của chủ ngựa: mọi hồ sơ PEDIGREE
     * của câu lạc bộ cộng với ngựa của chính người đó. Trước đây form lấy từ
     * GET /api/horses - chỉ ngựa của mình - nên không gắn được vào phả hệ chung
     * (Sunday Silence, Halo...). Lọc giới tính và tuổi để ở frontend; luật thật
     * vẫn ở checkParent.
     */
    public java.util.List<HorseResponse> parentCandidates(AuthenticatedUser user, Integer forHorseId) {
        if ("HORSE_OWNER".equals(user.role())) {
            return horseRepository.findParentCandidates(user.userId(), forHorseId == null ? 0 : forHorseId);
        }
        return horseRepository.findAll();
    }

    public java.util.List<HorseResponse> list(AuthenticatedUser user) {
        if ("HORSE_OWNER".equals(user.role())) {
            return horseRepository.findByOwner(user.userId());
        }
        // Góp ý hội đồng: Groom đi theo Head Trainer, nên chỉ thấy ngựa của đội mình.
        if ("GROOM".equals(user.role())) {
            return horseRepository.findForGroom(user.userId());
        }
        // V37: Head Trainer chỉ thấy những ngựa Club Manager đã giao cho mình quản lý.
        if ("HEAD_TRAINER".equals(user.role())) {
            return horseRepository.findByTrainer(user.userId());
        }
        return horseRepository.findAll();
    }

    /** generations = số đời tổ tiên cần lấy, kẹp trong khoảng 1 đến 5. */
    // Đọc mọi tổ tiên bằng MỘT câu SQL đệ quy (findAncestors), cho vào Map theo id, rồi dựng cây
    // trong Java bằng buildNode (đệ quy) - tránh gọi DB một lần cho mỗi nút.
    public PedigreeNode getPedigree(int horseId, int generations, AuthenticatedUser user) {
        checkCanView(getOrThrow(horseId), user);
        int depth = Math.max(1, Math.min(generations, 5));
        Map<Integer, HorseRepository.PedigreeRow> rows = new HashMap<>();
        horseRepository.findAncestors(horseId, depth).forEach(r -> rows.put(r.horseId(), r));
        return buildNode(horseId, rows, depth);
    }

    private PedigreeNode buildNode(Integer id, Map<Integer, HorseRepository.PedigreeRow> rows, int remaining) {
        if (id == null) return null;
        HorseRepository.PedigreeRow row = rows.get(id);
        if (row == null) return null;
        PedigreeNode sire = remaining > 0 ? buildNode(row.sireId(), rows, remaining - 1) : null;
        PedigreeNode dam = remaining > 0 ? buildNode(row.damId(), rows, remaining - 1) : null;
        return new PedigreeNode(row.horseId(), row.horseName(), row.registrationCode(),
                row.horseGender(), sire, dam);
    }

    /** V48: height, body condition score and aptitudes recorded when the horse arrived. */
    public Map<String, Object> getIntake(int horseId, AuthenticatedUser user) {
        HorseResponse horse = getOrThrow(horseId);
        checkCanView(horse, user);
        HorseRepository.Intake in = horseRepository.findIntake(horseId)
                .orElse(new HorseRepository.Intake(null, null, null, null, null));
        Map<String, Object> out = new HashMap<>();
        out.put("horseId", horseId);
        out.put("heightCm", in.heightCm());
        out.put("bcs", in.bcs());
        out.put("bcsGroup", HorsePolicy.bcsGroup(in.bcs()));
        out.put("surface", in.surface());
        out.put("distancePref", in.distancePref());
        out.put("runningStyle", in.runningStyle());
        out.put("growth", HorsePolicy.growthFor(horse.breed(), horse.dateOfBirth(), horse.weight(),
                in.heightCm(), java.time.LocalDate.now()));
        return out;
    }

    public HorseVitalsResponse getVitals(int horseId, AuthenticatedUser user) {
        HorseResponse horse = getOrThrow(horseId);
        checkCanView(horse, user);
        return new HorseVitalsResponse(horse.horseId(), horse.horseName(), horse.status(), horse.stallNo(),
                vitalsRepository.findActiveLock(horseId).orElse(null),
                vitalsRepository.findLatestSession(horseId).orElse(null),
                vitalsRepository.findOpenAlerts(horseId),
                vitalsRepository.findOpenInjuries(horseId));
    }

    // ---------- helper ----------

    // Dựng chuỗi "name: A -> B; dob: ..." chỉ gồm các trường thay đổi, ghi vào AUDIT_LOG.detail.
    private static String changes(HorseResponse before, HorseUpdateRequest after) {
        StringBuilder sb = new StringBuilder();
        java.util.function.BiConsumer<String, Object[]> diff = (name, v) -> {
            if (!java.util.Objects.equals(v[0], v[1])) {
                sb.append(sb.length() == 0 ? "" : "; ").append(name).append(": ").append(v[0]).append(" -> ").append(v[1]);
            }
        };
        diff.accept("name", new Object[] { before.horseName(), after.horseName() });
        diff.accept("breed", new Object[] { before.breed(), after.breed() });
        diff.accept("sex", new Object[] { before.horseGender(), after.horseGender() });
        diff.accept("dob", new Object[] { before.dateOfBirth(), after.dateOfBirth() });
        diff.accept("colour", new Object[] { before.color(), after.color() });
        diff.accept("sire", new Object[] { before.sireId(), after.sireId() });
        diff.accept("dam", new Object[] { before.damId(), after.damId() });
        return sb.length() == 0 ? null : (sb.length() > 500 ? sb.substring(0, 500) : sb.toString());
    }

    // Không có "private" = package-private: HorsePhotoService (cùng package) gọi được.
    HorseResponse getOrThrow(int horseId) {
        return horseRepository.findById(horseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Horse not found: #" + horseId));
    }

    void checkCanView(HorseResponse horse, AuthenticatedUser user) {
        if ("HORSE_OWNER".equals(user.role()) && horse.ownerId() != user.userId()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only view your own horses");
        }
        if ("GROOM".equals(user.role()) && !horseRepository.isInGroomTeam(horse.horseId(), user.userId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, horse.horseName() + " is not in your Head Trainer's team");
        }
    }

    private HorseRequest normalize(HorseRequest r) {
        return new HorseRequest(r.ownerId(), r.sireId(), r.damId(), r.horseName().trim(),
                r.registrationCode().trim().toUpperCase(), blankToNull(r.breed()),
                r.horseGender(), r.dateOfBirth(), blankToNull(r.color()), r.weight(),
                blankToNull(r.stallNo()), HorsePolicy.normalizeType(r.recordType()));
    }

    private void validateTypeRules(HorseRequest r) {
        Map<String, String> errors = new HashMap<>();
        String ageProblem = HorsePolicy.ageProblem(r.recordType(), r.dateOfBirth());
        if (ageProblem != null) errors.put("dateOfBirth", ageProblem);
        if (HorsePolicy.PEDIGREE.equals(r.recordType())) {
            if (r.stallNo() != null) errors.put("stallNo", "A pedigree record has no stall");
            if (r.weight() != null) errors.put("weight", "A pedigree record has no weight");
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid data", errors);
        }
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private void validate(HorseRequest r, int excludeId) {
        Map<String, String> errors = new HashMap<>();

        String format = HorsePolicy.codeProblem(r.registrationCode());
        if (format != null) {
            errors.put("registrationCode", format);
        } else if (horseRepository.existsByRegistrationCode(r.registrationCode(), excludeId)) {
            errors.put("registrationCode", "This registration code is already in use");
        }
        if (r.stallNo() != null && horseRepository.existsByStallNo(r.stallNo(), excludeId)) {
            errors.put("stallNo", "Another horse is already in this stall");
        }
        if (!horseRepository.isHorseOwner(r.ownerId())) {
            errors.put("ownerId", "The owner must be a Horse Owner account");
        }
        checkParent(r.sireId(), "sireId", "Sire", SIRE_GENDERS, excludeId, r.dateOfBirth(), errors);
        checkParent(r.damId(), "damId", "Dam", DAM_GENDERS, excludeId, r.dateOfBirth(), errors);
        if (r.sireId() != null && r.sireId().equals(r.damId())) {
            errors.put("damId", "Sire and dam cannot be the same horse");
        }

        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid data", errors);
        }
    }

    // Chủ ngựa chỉ được chọn cha/mẹ là hồ sơ PEDIGREE hoặc ngựa của chính mình (không lấy ngựa của
    // người khác làm cha mẹ). Giữ nguyên cha/mẹ đang có thì không kiểm.
    private void checkParentReachable(Integer parentId, Integer currentId, String field, int ownerId,
                                      Map<String, String> errors) {
        if (parentId == null || parentId.equals(currentId) || errors.containsKey(field)) return;
        horseRepository.findById(parentId).ifPresent(p -> {
            boolean pedigree = HorsePolicy.PEDIGREE.equals(HorsePolicy.normalizeType(p.recordType()));
            if (!pedigree && p.ownerId() != ownerId) {
                errors.put(field, "Choose a pedigree record or one of your own horses");
            }
        });
    }

    // Luật sinh học của phả hệ, thêm lỗi vào errors (không ném ngay) để báo một lần mọi ô sai.
    private void checkParent(Integer parentId, String field, String label,
                             Set<String> allowedGenders, int selfId, java.time.LocalDate childDob,
                             Map<String, String> errors) {
        if (parentId == null) return;
        if (parentId == selfId) {
            errors.put(field, label + " cannot be the horse itself");
            return;
        }
        horseRepository.findById(parentId).ifPresentOrElse(parent -> {
            if (parent.horseGender() != null && !allowedGenders.contains(parent.horseGender())) {
                errors.put(field, label + " has the wrong sex (sire: colt or stallion; dam: filly or mare)");
            }
            if (parent.dateOfBirth() != null && childDob != null
                    && parent.dateOfBirth().plusYears(HorsePolicy.FOAL_BELOW).isAfter(childDob)) {
                errors.put(field, label + " must be born at least "
                        + HorsePolicy.FOAL_BELOW + " years before this horse");
            }
            if (selfId > 0 && horseRepository.isAncestorOf(selfId, parentId)) {
                errors.put(field, label + " is not allowed: this horse is already an ancestor of "
                        + (parent.horseName() == null ? "that horse" : parent.horseName())
                        + ", so the pedigree would loop");
            }
        }, () -> errors.put(field, label + " does not exist"));
    }

    /** Audit detail for Club records: only the fields that changed, in plain words. */
    private static String identityChanges(HorseResponse before, HorseResponse after) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (!java.util.Objects.equals(before.registrationCode(), after.registrationCode())) {
            parts.add("Registration code: " + before.registrationCode() + " → " + after.registrationCode());
        }
        if (!java.util.Objects.equals(before.stallNo(), after.stallNo())) {
            parts.add("Stall: " + orNone(before.stallNo()) + " → " + orNone(after.stallNo()));
        }
        if (!java.util.Objects.equals(before.trainerId(), after.trainerId())) {
            parts.add("Head Trainer: " + orNone(before.trainerName()) + " → " + orNone(after.trainerName()));
        }
        return parts.isEmpty() ? "No change" : String.join(". ", parts);
    }

    private static String orNone(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }
}
