package racehorse.backend.horse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import racehorse.backend.auth.ApiException;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.UserRepository;
import racehorse.backend.notification.Notice;
import racehorse.backend.notification.NotificationService;

@Service
public class HorseService {

    private static final Set<String> SIRE_GENDERS = Set.of("COLT", "STALLION");
    private static final Set<String> DAM_GENDERS = Set.of("FILLY", "MARE");

    private final HorseRepository horseRepository;
    private final HorseVitalsRepository vitalsRepository;
    private final UserRepository userRepository;
    private final NotificationService notifications;

    public HorseService(HorseRepository horseRepository, HorseVitalsRepository vitalsRepository,
                        UserRepository userRepository) {
        this(horseRepository, vitalsRepository, userRepository, null);
    }

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
        HorseRequest request = normalize(raw);
        validate(request, 0);
        validateTypeRules(request);
        int id;
        try {
            id = horseRepository.insert(request);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "The registration code or stall is already in use");
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
        if (horseRepository.existsByRegistrationCode(code, horseId)) {
            errors.put("registrationCode", "This registration code is already in use");
        }
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
            throw new ApiException(HttpStatus.CONFLICT, "The registration code or stall has just been taken by someone else");
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
        // A NEW parent must be a pedigree record or one of the owner's own horses;
        // the parent already on the record is kept even if it is someone else's.
        checkParentReachable(request.sireId(), current.sireId(), "sireId", user.userId(), errors);
        checkParentReachable(request.damId(), current.damId(), "damId", user.userId(), errors);

        boolean pedigreeChanged = !java.util.Objects.equals(current.horseGender(), request.horseGender())
                || !java.util.Objects.equals(current.dateOfBirth(), request.dateOfBirth())
                || !java.util.Objects.equals(current.sireId(), request.sireId())
                || !java.util.Objects.equals(current.damId(), request.damId());
        if (pedigreeChanged) {
            boolean hasOffspring = horseRepository.hasOffspring(horseId);
            boolean hasTrained = vitalsRepository.findLatestSession(horseId).isPresent();
            if (hasOffspring || hasTrained) {
                errors.put("horseGender", "Sex, date of birth and parents are locked because "
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
        if ("GROOM".equals(user.role())) {
            return horseRepository.findActive();
        }
        // V37: Head Trainer chỉ thấy những ngựa Club Manager đã giao cho mình quản lý.
        if ("HEAD_TRAINER".equals(user.role())) {
            return horseRepository.findByTrainer(user.userId());
        }
        return horseRepository.findAll();
    }

    /** generations = số đời tổ tiên cần lấy, kẹp trong khoảng 1 đến 5. */
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

    HorseResponse getOrThrow(int horseId) {
        return horseRepository.findById(horseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Horse not found: #" + horseId));
    }

    void checkCanView(HorseResponse horse, AuthenticatedUser user) {
        if ("HORSE_OWNER".equals(user.role()) && horse.ownerId() != user.userId()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only view your own horses");
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

        if (horseRepository.existsByRegistrationCode(r.registrationCode(), excludeId)) {
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
