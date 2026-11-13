package racehorse.backend.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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

import racehorse.backend.dto.DischargeReferralRequest;
import racehorse.backend.dto.ExternalReferralRequest;
import racehorse.backend.dto.ExternalReferralResponse;
import racehorse.backend.security.AuthInterceptor;
import racehorse.backend.security.AuthenticatedUser;
import racehorse.backend.security.RequireRole;
import racehorse.backend.service.ExternalReferralService;

@RestController
@RequestMapping("/api/external-referrals")
public class ExternalReferralController {

    private final ExternalReferralService service;

    public ExternalReferralController(ExternalReferralService service) {
        this.service = service;
    }

    /** Tạo phiếu chuyển viện ngoài (Bác sĩ thú y). */
    @PostMapping
    @RequireRole({"VETERINARIAN"})
    public ResponseEntity<ExternalReferralResponse> create(
            @Valid @RequestBody ExternalReferralRequest request,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser vet,
            HttpServletRequest http) {
        ExternalReferralResponse body = service.referToHospital(request, vet, http.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** Danh sách ca chuyển viện ngoài (kèm lọc theo ngựa hoặc trạng thái). */
    @GetMapping
    @RequireRole({"VETERINARIAN", "HEAD_TRAINER", "CLUB_MANAGER", "GROOM", "HORSE_OWNER"})
    public List<ExternalReferralResponse> list(
            @RequestParam(value = "horseId", required = false) Integer horseId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.list(horseId, status, limit, user);
    }

    @GetMapping("/{id}")
    @RequireRole({"VETERINARIAN", "HEAD_TRAINER", "CLUB_MANAGER", "GROOM", "HORSE_OWNER"})
    public ExternalReferralResponse get(
            @PathVariable int id,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.get(id, user);
    }

    /** Tiếp nhận ngựa xuất viện từ bệnh viện ngoài trở về CLB. */
    @PutMapping("/{id}/discharge")
    @RequireRole({"VETERINARIAN"})
    public ExternalReferralResponse discharge(
            @PathVariable int id,
            @Valid @RequestBody DischargeReferralRequest request,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser vet,
            HttpServletRequest http) {
        return service.discharge(id, vet, request, http.getRemoteAddr());
    }
}
