package racehorse.backend.incident;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import racehorse.backend.auth.AuthInterceptor;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.auth.RequireRole;

/**
 * POST /api/incident-reports (multipart): báo sự cố tại chuồng, ảnh tuỳ chọn.
 * Danh sách và nhận ca vẫn ở IncidentController.
 */
@RestController
@RequestMapping("/api")
public class IncidentFilingController {

    private final IncidentFilingService service;

    public IncidentFilingController(IncidentFilingService service) {
        this.service = service;
    }

    @PostMapping(value = "/incident-reports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequireRole({"GROOM", "HEAD_TRAINER"})
    public ResponseEntity<IncidentReportResponse> file(
            @RequestParam("horseId") int horseId,
            @RequestParam(value = "symptoms", required = false) String symptoms,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "urgency", required = false) String urgency,
            @RequestPart(value = "photo", required = false) MultipartFile photo,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.file(horseId, symptoms, description, urgency, photo, user, http.getRemoteAddr()));
    }
}
