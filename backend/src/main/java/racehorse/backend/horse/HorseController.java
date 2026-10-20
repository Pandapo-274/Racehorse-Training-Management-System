package racehorse.backend.horse;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import racehorse.backend.auth.AuthInterceptor;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.RequireRole;

@RestController
@RequestMapping("/api/horses")
public class HorseController {

    private final HorseService horseService;

    public HorseController(HorseService horseService) {
        this.horseService = horseService;
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
