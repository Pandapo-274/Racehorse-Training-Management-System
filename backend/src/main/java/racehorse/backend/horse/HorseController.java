package racehorse.backend.horse;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    @PostMapping
    @RequireRole({"HEAD_TRAINER", "CLUB_MANAGER"})
    public ResponseEntity<HorseResponse> create(
            @Valid @RequestBody HorseRequest request,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        HorseResponse body = horseService.create(request, user, http.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PutMapping("/{id}")
    @RequireRole({"HEAD_TRAINER", "CLUB_MANAGER"})
    public HorseResponse update(
            @PathVariable int id,
            @Valid @RequestBody HorseRequest request,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return horseService.update(id, request, user, http.getRemoteAddr());
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