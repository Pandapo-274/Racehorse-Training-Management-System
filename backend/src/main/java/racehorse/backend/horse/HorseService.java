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

    @Transactional
    public HorseResponse update(int horseId, HorseRequest raw, AuthenticatedUser user, String ip) {
        getOrThrow(horseId);
        HorseRequest request = normalize(raw);
        validate(request, horseId);
        try {
            horseRepository.update(horseId, request);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Dữ liệu vi phạm ràng buộc (mã đăng ký hoặc chuồng đã tồn tại)");
        }
        userRepository.writeAudit(user.userId(), "HORSE_UPDATE", "HORSE", horseId, "SUCCESS", ip);
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
                blankToNull(r.stallNo()));
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
        checkParent(r.sireId(), "sireId", "Ngựa cha", SIRE_GENDERS, excludeId, errors);
        checkParent(r.damId(), "damId", "Ngựa mẹ", DAM_GENDERS, excludeId, errors);
        if (r.sireId() != null && r.sireId().equals(r.damId())) {
            errors.put("damId", "Cha và mẹ không được là cùng một con");
        }

        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Dữ liệu không hợp lệ", errors);
        }
    }

    private void checkParent(Integer parentId, String field, String label,
                             Set<String> allowedGenders, int selfId, Map<String, String> errors) {
        if (parentId == null) return;
        if (parentId == selfId) {
            errors.put(field, label + " không được là chính con ngựa này");
            return;
        }
        horseRepository.findById(parentId).ifPresentOrElse(parent -> {
            if (parent.horseGender() != null && !allowedGenders.contains(parent.horseGender())) {
                errors.put(field, label + " có giới tính không phù hợp");
            }
        }, () -> errors.put(field, label + " không tồn tại"));
    }
}