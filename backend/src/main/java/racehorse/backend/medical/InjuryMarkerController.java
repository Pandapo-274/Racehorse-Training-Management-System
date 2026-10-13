package racehorse.backend.medical;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

/**
 * UC15 - Map Injury on 3D Anatomy Model (3D Injury Marker).
 *
 * Chỉ bác sĩ thú y ghim và xoá dấu. Các vai trò khác đọc được, vì mô hình
 * giải phẫu là thứ huấn luyện viên xem trước khi xếp bài tập nặng, và chủ
 * ngựa có quyền biết ngựa mình bị gì - nhưng chỉ người khám mới được ghim.
 */
@RestController
@RequestMapping("/api/injuries")
public class InjuryMarkerController {

    private final InjuryMarkerService service;

    public InjuryMarkerController(InjuryMarkerService service) {
        this.service = service;
    }

    @PostMapping
    @RequireRole({"VETERINARIAN"})
    public ResponseEntity<InjuryMarkerRow> mark(
            @Valid @RequestBody InjuryMarkerRequest request,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser vet,
            HttpServletRequest http) {
        InjuryMarkerRow body = service.mark(request, vet, http.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** Mọi dấu của một con ngựa, kể cả dấu đã lành, để mô hình vẽ cả bệnh sử. */
    @GetMapping
    public List<InjuryMarkerRow> listForHorse(
            @RequestParam int horseId,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.listForHorse(horseId, user);
    }

    /** Xoá một dấu ghim sai chỗ. Chỉ được khi dấu còn TREATING và không bị lệnh khoá trỏ vào. */
    @DeleteMapping("/{id}")
    @RequireRole({"VETERINARIAN"})
    public ResponseEntity<Void> unmark(
            @PathVariable int id,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser vet,
            HttpServletRequest http) {
        service.unmark(id, vet, http.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }
}
