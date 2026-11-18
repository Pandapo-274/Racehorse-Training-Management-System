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

import racehorse.backend.dto.TreatmentRequest;
import racehorse.backend.entity.TreatmentRow;
import racehorse.backend.security.AuthInterceptor;
import racehorse.backend.security.AuthenticatedUser;
import racehorse.backend.security.RequireRole;
import racehorse.backend.service.TreatmentReminderService;
import racehorse.backend.service.TreatmentService;

/**
 * UC14 - đơn thuốc kèm một lần khám.
 *
 * Chỉ bác sĩ thú y kê và kết thúc. Các vai trò khác đọc được, vì groom là
 * người thật sự cho thuốc theo đơn và chủ ngựa có quyền biết ngựa mình đang
 * dùng gì - nhưng kê thuốc thì chỉ người khám.
 */
/*
 *   POST /api/treatments                   bác sĩ kê đơn (gắn với một lần khám)
 *   GET  /api/treatments?horseId=          đơn của một ngựa (kể cả đã hết hạn)
 *   GET  /api/treatments/active            mọi đơn đang dùng trên toàn đàn (nhân sự chuồng trại)
 *   GET  /api/treatments/by-record/{id}    đơn theo một lần khám
 *   PUT  /api/treatments/{id}/consent      chủ ngựa đồng ý điều trị lớn
 *   PUT  /api/treatments/{id}/reject-consent chủ ngựa từ chối điều trị lớn
 *   PUT  /api/treatments/{id}/end          bác sĩ kết thúc đơn hôm nay
 */
@RestController
@RequestMapping("/api/treatments")
public class TreatmentController {

    private final TreatmentService service;
    private final TreatmentReminderService reminders;

    public TreatmentController(TreatmentService service, TreatmentReminderService reminders) {
        this.service = service;
        this.reminders = reminders;
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
        // Mở danh sách đơn đang dùng cũng là lúc chạy luôn hai việc nhắc của TreatmentReminderService
        // (ngoài lịch chạy 6 giờ một lần), để bác sĩ thấy thông báo mới ngay.
        reminders.checkCompletedTreatments();
        reminders.checkWaitingIncidentReports();
        return service.listActive();
    }

    /** Đơn thuốc kê theo một lần khám cụ thể. */
    @GetMapping("/by-record/{recordId}")
    public List<TreatmentRow> listForRecord(
            @PathVariable int recordId,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.listForRecord(recordId, user);
    }

    // (Javadoc ngay dưới đây thuộc về endpoint /end ở cuối file; endpoint này là chủ ngựa ĐỒNG Ý điều trị.)
    /** Kết thúc một mục điều trị hôm nay. Không có đường xoá - xem TreatmentService.end. */
    @PutMapping("/{id}/consent")
    @RequireRole({"HORSE_OWNER"})
    public TreatmentRow consent(
            @PathVariable int id,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return service.grantConsent(id, user, http.getRemoteAddr());
    }

    @PutMapping("/{id}/reject-consent")
    @RequireRole({"HORSE_OWNER"})
    public TreatmentRow rejectConsent(
            @PathVariable int id,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return service.rejectConsent(id, user, http.getRemoteAddr());
    }

    @PutMapping("/{id}/end")
    @RequireRole({"VETERINARIAN"})
    public TreatmentRow end(
            @PathVariable int id,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser vet,
            HttpServletRequest http) {
        return service.end(id, vet, http.getRemoteAddr());
    }
}
