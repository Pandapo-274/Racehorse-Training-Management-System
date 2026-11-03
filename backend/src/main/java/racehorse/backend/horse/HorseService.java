package racehorse.backend.horse;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import racehorse.backend.auth.ApiException;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.UserRepository;

@Service
public class HorseService {

    private static final Set<String> SIRE_GENDERS = Set.of("COLT", "STALLION");
    private static final Set<String> DAM_GENDERS = Set.of("FILLY", "MARE");

    private final HorseRepository horseRepository;
    private final HorseVitalsRepository vitalsRepository;
    private final UserRepository userRepository;

    public HorseService(HorseRepository horseRepository, HorseVitalsRepository vitalsRepository,
                        UserRepository userRepository) {
        this.horseRepository = horseRepository;
        this.vitalsRepository = vitalsRepository;
        this.userRepository = userRepository;
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
                    "Dữ liệu vi phạm ràng buộc (mã đăng ký hoặc chuồng đã tồn tại)");
        }
        userRepository.writeAudit(user.userId(), "HORSE_CREATE", "HORSE", id, "SUCCESS", ip);
        return getOrThrow(id);
    }

    /**
     * Club Manager sửa mã đăng ký và số chuồng (vd. nhập nhầm lúc duyệt, hoặc
     * đổi chuồng). Đây là hai định danh do câu lạc bộ cấp nên chủ ngựa không sửa
     * được; trước đây cũng không ai sửa được.
     */
    @Transactional
    public HorseResponse updateIdentity(int horseId, HorseIdentityRequest raw, AuthenticatedUser user, String ip) {
        HorseResponse current = getOrThrow(horseId);
        String code = raw.registrationCode().trim().toUpperCase(java.util.Locale.ROOT);
        String stall = blankToNull(raw.stallNo());

        Map<String, String> errors = new HashMap<>();
        if (horseRepository.existsByRegistrationCode(code, horseId)) {
            errors.put("registrationCode", "Mã đăng ký đã tồn tại");
        }
        boolean pedigree = HorsePolicy.PEDIGREE.equals(HorsePolicy.normalizeType(current.recordType()));
        if (stall != null && (pedigree || current.isWithdrawn())) {
            errors.put("stallNo", pedigree ? "A pedigree record has no stall" : "A withdrawn horse has no stall");
        } else if (stall == null && !pedigree && !current.isWithdrawn()) {
            errors.put("stallNo", "A club horse needs a stall");
        } else if (stall != null && horseRepository.existsByStallNo(stall, horseId)) {
            errors.put("stallNo", "Chuồng này đã có ngựa khác");
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Dữ liệu không hợp lệ", errors);
        }
        try {
            horseRepository.updateIdentity(horseId, code, stall);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "Mã đăng ký hoặc chuồng vừa bị người khác dùng");
        }
        userRepository.writeAudit(user.userId(), "HORSE_IDENTITY_UPDATE", "HORSE", horseId, "SUCCESS", ip,
                "code: " + current.registrationCode() + " -> " + code
                        + "; stall: " + current.stallNo() + " -> " + stall);
        return getOrThrow(horseId);
    }

    /**
     * Chủ ngựa sửa thông tin cơ bản của NGỰA CỦA MÌNH.
     *
     * Quyền sở hữu kiểm ở đây (không chỉ ở controller) vì controller chỉ biết
     * role, còn "ngựa này có phải của người đang gọi không" là chuyện của dữ liệu.
     * Mã đăng ký, chuồng, chủ, cân nặng, loại hồ sơ, trạng thái: giữ nguyên.
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
        checkParent(request.sireId(), "sireId", "Ngựa cha", SIRE_GENDERS, horseId, request.dateOfBirth(), errors);
        checkParent(request.damId(), "damId", "Ngựa mẹ", DAM_GENDERS, horseId, request.dateOfBirth(), errors);

        /* Lý lịch (giới tính, ngày sinh, cha mẹ) khoá lại khi con ngựa đã là
           cha/mẹ của ngựa khác hoặc đã có buổi tập: đổi lúc đó làm phả hệ của
           ngựa con mâu thuẫn (vd. ngựa cha thành MARE), hoặc làm sai hồ sơ đã
           dùng để xếp giáo án. Tên, giống, màu lông vẫn sửa được. */
        boolean pedigreeChanged = !java.util.Objects.equals(current.horseGender(), request.horseGender())
                || !java.util.Objects.equals(current.dateOfBirth(), request.dateOfBirth())
                || !java.util.Objects.equals(current.sireId(), request.sireId())
                || !java.util.Objects.equals(current.damId(), request.damId());
        if (pedigreeChanged) {
            boolean hasOffspring = horseRepository.findAll().stream().anyMatch(h ->
                    java.util.Objects.equals(h.sireId(), horseId) || java.util.Objects.equals(h.damId(), horseId));
            boolean hasTrained = vitalsRepository.findLatestSession(horseId).isPresent();
            if (hasOffspring || hasTrained) {
                errors.put("horseGender", "Sex, date of birth and parents are locked because "
                        + current.horseName() + (hasOffspring ? " is already a parent of another horse"
                                                              : " already has training sessions")
                        + ". Ask the club to correct them");
            }
        }
        if (request.sireId() != null && request.sireId().equals(request.damId())) {
            errors.put("damId", "Cha và mẹ không được là cùng một con");
        }
        // Sửa ngày sinh không được là cách lách luật tuổi.
        String ageProblem = HorsePolicy.ageProblem(current.recordType(), request.dateOfBirth());
        if (ageProblem != null) errors.put("dateOfBirth", ageProblem);

        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Dữ liệu không hợp lệ", errors);
        }

        horseRepository.updateBasics(horseId, request);
        userRepository.writeAudit(user.userId(), "HORSE_UPDATE", "HORSE", horseId, "SUCCESS", ip,
                changes(current, request));
        return getOrThrow(horseId);
    }

    public HorseResponse get(int horseId, AuthenticatedUser user) {
        HorseResponse horse = getOrThrow(horseId);
        checkCanView(horse, user);
        return horse;
    }

    public java.util.List<HorseResponse> list(AuthenticatedUser user) {
        // Chủ ngựa chỉ thấy ngựa của mình, các role còn lại thấy toàn bộ
        if ("HORSE_OWNER".equals(user.role())) {
            return horseRepository.findByOwner(user.userId());
        }
        // Groom làm việc với ngựa trong chuồng; ngựa đã rút không còn ở đó.
        // Manager, Trainer, Vet vẫn thấy cả ngựa đã rút để tra lịch sử.
        if ("GROOM".equals(user.role())) {
            return horseRepository.findActive();
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

    /** Giá trị cũ và mới của những trường đã đổi, ghi vào AUDIT_LOG.detail (V20). */
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
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Không tìm thấy ngựa #" + horseId));
    }

    void checkCanView(HorseResponse horse, AuthenticatedUser user) {
        if ("HORSE_OWNER".equals(user.role()) && horse.ownerId() != user.userId()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Bạn không có quyền xem ngựa này");
        }
    }

    /** Chuỗi rỗng -> null. Quan trọng với stall_no: unique index chỉ cho phép nhiều NULL, không cho nhiều "". */
    private HorseRequest normalize(HorseRequest r) {
        return new HorseRequest(r.ownerId(), r.sireId(), r.damId(), r.horseName().trim(),
                r.registrationCode().trim().toUpperCase(), blankToNull(r.breed()),
                r.horseGender(), r.dateOfBirth(), blankToNull(r.color()), r.weight(),
                blankToNull(r.stallNo()), HorsePolicy.normalizeType(r.recordType()));
    }

    /** Luật theo loại hồ sơ: tuổi cho ACADEMY; PEDIGREE không có chuồng và cân nặng. */
    private void validateTypeRules(HorseRequest r) {
        Map<String, String> errors = new HashMap<>();
        String ageProblem = HorsePolicy.ageProblem(r.recordType(), r.dateOfBirth());
        if (ageProblem != null) errors.put("dateOfBirth", ageProblem);
        if (HorsePolicy.PEDIGREE.equals(r.recordType())) {
            if (r.stallNo() != null) errors.put("stallNo", "A pedigree record has no stall");
            if (r.weight() != null) errors.put("weight", "A pedigree record has no weight");
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Dữ liệu không hợp lệ", errors);
        }
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** excludeId = 0 khi tạo mới, = id ngựa khi sửa. */
    private void validate(HorseRequest r, int excludeId) {
        Map<String, String> errors = new HashMap<>();

        if (horseRepository.existsByRegistrationCode(r.registrationCode(), excludeId)) {
            errors.put("registrationCode", "Mã đăng ký đã tồn tại");
        }
        if (r.stallNo() != null && horseRepository.existsByStallNo(r.stallNo(), excludeId)) {
            errors.put("stallNo", "Chuồng này đã có ngựa khác");
        }
        if (!horseRepository.isHorseOwner(r.ownerId())) {
            errors.put("ownerId", "Chủ ngựa phải là tài khoản có role HORSE_OWNER");
        }
        checkParent(r.sireId(), "sireId", "Ngựa cha", SIRE_GENDERS, excludeId, r.dateOfBirth(), errors);
        checkParent(r.damId(), "damId", "Ngựa mẹ", DAM_GENDERS, excludeId, r.dateOfBirth(), errors);
        if (r.sireId() != null && r.sireId().equals(r.damId())) {
            errors.put("damId", "Cha và mẹ không được là cùng một con");
        }

        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Dữ liệu không hợp lệ", errors);
        }
    }

    private void checkParent(Integer parentId, String field, String label,
                             Set<String> allowedGenders, int selfId, java.time.LocalDate childDob,
                             Map<String, String> errors) {
        if (parentId == null) return;
        if (parentId == selfId) {
            errors.put(field, label + " không được là chính con ngựa này");
            return;
        }
        horseRepository.findById(parentId).ifPresentOrElse(parent -> {
            if (parent.horseGender() != null && !allowedGenders.contains(parent.horseGender())) {
                errors.put(field, label + " có giới tính không phù hợp");
            }
            /* Cha mẹ phải lớn hơn con ít nhất FOAL_BELOW năm: ngựa dưới tuổi đó
               chưa sinh sản được. */
            if (parent.dateOfBirth() != null && childDob != null
                    && parent.dateOfBirth().plusYears(HorsePolicy.FOAL_BELOW).isAfter(childDob)) {
                errors.put(field, label + " phải sinh trước con ngựa này ít nhất "
                        + HorsePolicy.FOAL_BELOW + " năm");
            }
            /* Chặn vòng lặp phả hệ. Phép kiểm parentId == selfId ở trên chỉ bắt
               được vòng dài một bước (tự làm cha mình); vòng dài hai bước -
               A là cha của B trong khi B đã là cha của A - vẫn lọt, và kết quả
               là cây phả hệ hiện một con ngựa làm ông của chính nó.

               selfId = 0 khi đang TẠO ngựa mới: con ngựa chưa tồn tại nên
               chưa thể là tổ tiên của ai, bỏ qua luôn khỏi phải hỏi database. */
            if (selfId > 0 && horseRepository.isAncestorOf(selfId, parentId)) {
                errors.put(field, label + " không hợp lệ: con ngựa này đã là tổ tiên của "
                        + (parent.horseName() == null ? "con ngựa đó" : parent.horseName())
                        + ", gán như vậy sẽ tạo vòng lặp trong phả hệ");
            }
        }, () -> errors.put(field, label + " không tồn tại"));
    }
}
