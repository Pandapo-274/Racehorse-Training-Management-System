package racehorse.backend.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import racehorse.backend.dto.ReviewRequest;
import racehorse.backend.dto.SubmitRequest;
import racehorse.backend.entity.HorseRequestRow;
import racehorse.backend.security.AuthInterceptor;
import racehorse.backend.security.AuthenticatedUser;
import racehorse.backend.security.RequireRole;
import racehorse.backend.service.ContractService;
import racehorse.backend.service.HorsePhotoService;
import racehorse.backend.service.HorseRequestService;
import racehorse.backend.service.HorseService;

/**
 * Chủ ngựa xin học viện đăng ký một con ngựa.
 *
 * Tồn tại vì HorseController.create chỉ cho HEAD_TRAINER và CLUB_MANAGER gọi,
 * nên chủ ngựa mới vào không có đường nào báo với học viện rằng mình có ngựa.
 * Đây là đường đó - và nó không phải cửa sau: yêu cầu chỉ thành hồ sơ ngựa
 * khi học viện bấm duyệt, và lúc duyệt vẫn đi qua đúng HorseService.create.
 *
 * ownerId luôn lấy từ token, không nhận từ client, nên không ai gửi yêu cầu
 * thay người khác được.
 */
/*
 * Ghi chú cập nhật: chú thích trên nhắc HorseController.create, nhưng endpoint POST /api/horses
 * đã bị bỏ; HorseService.create giờ chỉ được gọi nội bộ từ HorseRequestService.approve.
 *
 * Vòng đời một yêu cầu (bảng HORSE_REQUEST):
 *   PENDING --(Manager duyệt: cấp mã đăng ký + chuồng [+ HLV])--> APPROVED (tạo dòng HORSE)
 *   PENDING --(Manager từ chối, bắt buộc lý do)----------------> REJECTED
 *   PENDING --(chủ ngựa tự huỷ)--------------------------------> CANCELLED
 */
@RestController
@RequestMapping("/api/horse-requests")
public class HorseRequestController {

    private final HorseRequestService service;
    private final HorsePhotoService photos;
    private final ContractService contracts;

    public HorseRequestController(HorseRequestService service, HorsePhotoService photos,
                                  ContractService contracts) {
        this.service = service;
        this.photos = photos;
        this.contracts = contracts;
    }

    /** V48: ảnh của yêu cầu (bắt buộc với ngựa vào CLB trước khi Manager duyệt). Phần tên "file". */
    @PostMapping(value = "/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequireRole({"HORSE_OWNER"})
    public HorseRequestRow uploadPhoto(
            @PathVariable int id,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return photos.uploadRequestPhoto(id, file, user, http.getRemoteAddr());
    }

    /** Head Trainer chủ ngựa có thể xin: chỉ người còn nhận thêm ngựa, chỉ tên. */
    @GetMapping("/trainer-options")
    @RequireRole({"HORSE_OWNER"})
    public List<Map<String, Object>> trainerOptions() {
        return service.trainerOptions();
    }

    /** Phí tháng và chiết khấu theo gói, để form tính trước giá trị hợp đồng. */
    @GetMapping("/pricing")
    @RequireRole({"HORSE_OWNER", "CLUB_MANAGER"})
    public ContractService.Pricing pricing() {
        return contracts.pricing();
    }

    @PostMapping
    @RequireRole({"HORSE_OWNER"})
    public ResponseEntity<HorseRequestRow> submit(
            @Valid @RequestBody SubmitRequest request,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        HorseRequestRow body = service.submit(request, user, http.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /**
     * Chủ ngựa nhận yêu cầu của mình; Club Manager nhận cả hàng chờ. Trước đây
     * không có @RequireRole nên Groom, Vet, Head Trainer cũng đọc được hàng chờ
     * kèm tên chủ ngựa và ghi chú của họ.
     */
    @GetMapping
    @RequireRole({"HORSE_OWNER", "CLUB_MANAGER"})
    public List<HorseRequestRow> list(
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user) {
        return service.list(user);
    }

    /** Chủ ngựa huỷ yêu cầu của mình khi nó còn đang chờ (V15). */
    @PutMapping("/{id}/cancel")
    @RequireRole({"HORSE_OWNER"})
    public HorseRequestRow cancel(
            @PathVariable int id,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return service.cancel(id, user, http.getRemoteAddr());
    }

    @PutMapping("/{id}/approve")
    @RequireRole({"CLUB_MANAGER"})
    public HorseRequestRow approve(
            @PathVariable int id,
            @Valid @RequestBody ReviewRequest review,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return service.approve(id, review, user, http.getRemoteAddr());
    }

    @PutMapping("/{id}/reject")
    @RequireRole({"CLUB_MANAGER"})
    public HorseRequestRow reject(
            @PathVariable int id,
            @Valid @RequestBody ReviewRequest review,
            @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
            HttpServletRequest http) {
        return service.reject(id, review, user, http.getRemoteAddr());
    }
}
