package racehorse.backend.medical;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 * UC14 - Log Medical Exam & Diagnosis Record.
 *
 * Chỉ bác sĩ thú y được GHI. Các vai trò khác đọc được, vì hồ sơ bệnh án là
 * thứ huấn luyện viên cần trước khi xếp lịch và chủ ngựa có quyền biết về ngựa
 * của mình - nhưng chẩn đoán thì chỉ người khám mới được viết.
 */
@RestController
@RequestMapping("/api/medical-records")
public class MedicalRecordController {

    private final MedicalRecordService service;

    public MedicalRecordController(MedicalRecordService service) {
        this.service = service;
    }

    /** Ghi một lần khám. vetId lấy từ token, không nhận từ client. */
    @PostMapping
    @RequireRole({"VETERINARIAN"})
    public ResponseEntity<MedicalRecordRow> log(
            @Valid @RequestBody MedicalExamRequest request,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser vet,
            HttpServletRequest http) {
        MedicalRecordRow body = service.log(request, vet, http.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** Bệnh sử của một con ngựa, mới nhất lên trước. */
    @GetMapping
    public List<MedicalRecordRow> listForHorse(
            @RequestParam int horseId,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.listForHorse(horseId, user);
    }

    @GetMapping("/{id}")
    public MedicalRecordRow get(
            @PathVariable int id,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.get(id, user);
    }
}
