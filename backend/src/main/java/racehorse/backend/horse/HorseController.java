package racehorse.backend.horse;

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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import racehorse.backend.auth.AuthInterceptor;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.RequireRole;

@RestController
@RequestMapping("/api/horses")
public class HorseController {

    private final HorseService horseService;
    private final HorsePhotoService photoService;

    public HorseController(HorseService horseService, HorsePhotoService photoService) {
        this.horseService = horseService;
        this.photoService = photoService;
    }

    /*
     * KHÔNG còn POST /api/horses.
     * Ngựa chỉ vào hệ thống qua đường duyệt: chủ ngựa gửi yêu cầu
     * (POST /api/horse-requests), Club Manager duyệt và cấp mã đăng ký + chuồng.
     * Manager và Head Trainer chỉ XEM danh sách ngựa, không sửa hồ sơ ngựa.
     */

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
