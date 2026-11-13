package racehorse.backend.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import racehorse.backend.dto.HorseIdentityRequest;
import racehorse.backend.dto.HorseResponse;
import racehorse.backend.dto.HorseUpdateRequest;
import racehorse.backend.dto.HorseVitalsResponse;
import racehorse.backend.dto.PedigreeNode;
import racehorse.backend.repository.HorseRepository;
import racehorse.backend.security.AuthInterceptor;
import racehorse.backend.security.AuthenticatedUser;
import racehorse.backend.security.RequireRole;
import racehorse.backend.service.HorseAdminService;
import racehorse.backend.service.HorsePhotoService;
import racehorse.backend.service.HorseService;

@RestController
@RequestMapping("/api/horses")
public class HorseController {

    private final HorseService horseService;
    private final HorsePhotoService photoService;

    private final HorseAdminService adminService;

    public HorseController(HorseService horseService, HorsePhotoService photoService,
                           HorseAdminService adminService) {
        this.horseService = horseService;
        this.photoService = photoService;
        this.adminService = adminService;
    }

    /** Hồ sơ phả hệ của câu lạc bộ + ngựa của chính chủ ngựa: danh sách chọn cha / mẹ. */
    @GetMapping("/parent-candidates")
    @RequireRole({"HORSE_OWNER", "CLUB_MANAGER"})
    public List<HorseResponse> parentCandidates(@RequestParam(required = false) Integer horseId,
                                                @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return horseService.parentCandidates(user, horseId);
    }

    /** Club Manager chuyển ngựa sang chủ khác (bán, tặng). Bắt buộc lý do. */
    @PutMapping("/{id}/owner")
    @RequireRole({"CLUB_MANAGER"})
    public HorseResponse transferOwner(@PathVariable int id, @Valid @RequestBody TransferRequest request,
                                       @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
                                       HttpServletRequest http) {
        return adminService.transferOwner(id, request.ownerId(), request.reason(), user, http.getRemoteAddr());
    }

    /** Club Manager nhận lại ngựa đã rút, xếp chuồng mới. */
    @PutMapping("/{id}/readmit")
    @RequireRole({"CLUB_MANAGER"})
    public HorseResponse readmit(@PathVariable int id, @Valid @RequestBody ReadmitRequest request,
                                 @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
                                 HttpServletRequest http) {
        return adminService.readmit(id, request.stallNo(), user, http.getRemoteAddr());
    }

    public record TransferRequest(
            @jakarta.validation.constraints.NotNull(message = "New owner is required") Integer ownerId,
            @jakarta.validation.constraints.NotBlank(message = "Reason is required")
            @jakarta.validation.constraints.Size(max = 255, message = "Reason must be at most 255 characters")
            String reason) {}

    public record ReadmitRequest(
            @jakarta.validation.constraints.NotBlank(message = "Stall is required")
            @jakarta.validation.constraints.Size(max = 20, message = "Stall must be at most 20 characters")
            String stallNo) {}

    /*
     * KHÔNG còn POST /api/horses.
     * Ngựa chỉ vào hệ thống qua đường duyệt: chủ ngựa gửi yêu cầu
     * (POST /api/horse-requests), Club Manager duyệt và cấp mã đăng ký + chuồng.
     * Manager và Head Trainer chỉ XEM danh sách ngựa, không sửa hồ sơ ngựa.
     */
    /** V37: Danh sách Head Trainer đang ACTIVE cùng số ngựa đang quản lý & hạn mức tối đa. */
    @GetMapping("/trainers")
    @RequireRole({"CLUB_MANAGER", "HEAD_TRAINER"})
    public List<HorseRepository.TrainerSummary> listTrainers() {
        return horseService.listActiveTrainers();
    }

    /** Chỉ chủ ngựa, và chỉ ngựa của chính mình (service kiểm quyền sở hữu). */
    @PutMapping("/{id}")
    @RequireRole({"HORSE_OWNER"})
    public HorseResponse update(
            @PathVariable int id,
            @Valid @RequestBody HorseUpdateRequest request,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return horseService.updateByOwner(id, request, user, http.getRemoteAddr());
    }

    /** V41: the club manager corrects the sire / dam on record, with a reason. */
    @PutMapping("/{id}/parents")
    @RequireRole({"CLUB_MANAGER"})
    public HorseResponse correctParents(
            @PathVariable int id,
            @Valid @RequestBody ParentsRequest request,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return horseService.correctParents(id, request.sireId(), request.damId(), request.reason(),
                user, http.getRemoteAddr());
    }

    public record ParentsRequest(
            Integer sireId,
            Integer damId,
            @jakarta.validation.constraints.NotBlank(message = "Say why the parents are corrected")
            @jakarta.validation.constraints.Size(max = 200, message = "Reason must be at most 200 characters")
            String reason) {}

    /** Club Manager sửa mã đăng ký, số chuồng và điều phối Head Trainer phụ trách. */
    @PutMapping("/{id}/identity")
    @RequireRole({"CLUB_MANAGER"})
    public HorseResponse updateIdentity(
            @PathVariable int id,
            @Valid @RequestBody HorseIdentityRequest request,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return horseService.updateIdentity(id, request, user, http.getRemoteAddr());
    }

    /** Chủ ngựa tải ảnh cho ngựa của mình. Tên phần phải là "file", giống avatar. */
    @PostMapping(value = "/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequireRole({"HORSE_OWNER"})
    public HorseResponse uploadPhoto(
            @PathVariable int id,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return photoService.upload(id, file, user, http.getRemoteAddr());
    }

    @DeleteMapping("/{id}/photo")
    @RequireRole({"HORSE_OWNER"})
    public HorseResponse removePhoto(
            @PathVariable int id,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return photoService.remove(id, user, http.getRemoteAddr());
    }

    @GetMapping
    public List<HorseResponse> list(@RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return horseService.list(user);
    }

    @GetMapping("/{id}")
    public HorseResponse get(@PathVariable int id,
                             @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return horseService.get(id, user);
    }

    @GetMapping("/{id}/pedigree")
    public PedigreeNode pedigree(@PathVariable int id,
                                 @RequestParam(defaultValue = "3") int generations,
                                 @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return horseService.getPedigree(id, generations, user);
    }

    @GetMapping("/{id}/vitals")
    public HorseVitalsResponse vitals(@PathVariable int id,
                                      @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return horseService.getVitals(id, user);
    }
}
