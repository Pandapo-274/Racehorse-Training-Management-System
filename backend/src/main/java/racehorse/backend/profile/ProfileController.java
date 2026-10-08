package racehorse.backend.profile;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import racehorse.backend.auth.AuthInterceptor;
import racehorse.backend.auth.AuthenticatedUser;

/**
 * Mọi endpoint ở đây chỉ thao tác trên CHÍNH người đang đăng nhập (userId lấy từ token,
 * không nhận userId từ client). Nhờ vậy user A không thể sửa hồ sơ của user B.
 */
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    public ProfileResponse get(@RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return profileService.getProfile(user.userId());
    }

    @PutMapping
    public ProfileResponse update(@RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
                                  @Valid @RequestBody UpdateProfileRequest request,
                                  HttpServletRequest http) {
        return profileService.updateProfile(user.userId(), request, http.getRemoteAddr());
    }

    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProfileResponse uploadAvatar(@RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
                                        @RequestParam(value = "file", required = false) MultipartFile file,
                                        HttpServletRequest http) {
        return profileService.uploadAvatar(user.userId(), file, http.getRemoteAddr());
    }
}
