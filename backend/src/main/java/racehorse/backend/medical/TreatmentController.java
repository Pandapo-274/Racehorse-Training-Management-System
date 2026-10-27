package racehorse.backend.medical;

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

/**
 * UC14 - đơn thuốc kèm một lần khám.
 *
 * Chỉ bác sĩ thú y kê và kết thúc. Các vai trò khác đọc được, vì groom là
 * người thật sự cho thuốc theo đơn và chủ ngựa có quyền biết ngựa mình đang
 * dùng gì - nhưng kê thuốc thì chỉ người khám.
 */
@RestController
@RequestMapping("/api/treatments")
public class TreatmentController {

    private final TreatmentService service;

    public TreatmentController(TreatmentService service) {
        this.service = service;
    }

    @PostMapping
    @RequireRole({"VETERINARIAN"})
    public ResponseEntity<TreatmentRow> prescribe(
            @Valid @RequestBody TreatmentRequest request,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser vet,
            HttpServletRequest http) {
        TreatmentRow body = service.prescribe(request, vet, http.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /**
     * Đơn thuốc của một con ngựa, kể cả mục đã hết hạn.
     *
     * Không gắn @RequireRole: service đã kiểm quyền sở hữu cho HORSE_OWNER,
     * giống MedicalRecordController và InjuryMarkerController.
     */
    @GetMapping
    public List<TreatmentRow> listForHorse(
            @RequestParam int horseId,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.listForHorse(horseId, user);
    }

    /**
     * Mọi đơn thuốc đang dùng trên toàn đàn: danh sách mở đầu của trang đơn thuốc.
     *
     * Giới hạn cho nhân sự trong chuồng, giống bảng nhắc lịch UC17: chủ ngựa không
     * xem được thuốc của cả đàn mà chỉ xem ngựa của mình qua GET ?horseId=. Groom
     * nằm trong danh sách vì họ là người cho thuốc theo đơn.
     */
    @GetMapping("/active")
    @RequireRole({"VETERINARIAN", "HEAD_TRAINER", "GROOM", "CLUB_MANAGER"})
    public List<TreatmentRow> listActive() {
        return service.listActive();
    }

    /** Đơn thuốc kê theo một lần khám cụ thể. */
    @GetMapping("/by-record/{recordId}")
    public List<TreatmentRow> listForRecord(
            @PathVariable int recordId,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.listForRecord(recordId, user);
    }

    /** Kết thúc một mục điều trị hôm nay. Không có đường xoá - xem TreatmentService.end. */
    @PutMapping("/{id}/end")
    @RequireRole({"VETERINARIAN"})
    public TreatmentRow end(
            @PathVariable int id,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser vet,
            HttpServletRequest http) {
        return service.end(id, vet, http.getRemoteAddr());
    }
}
