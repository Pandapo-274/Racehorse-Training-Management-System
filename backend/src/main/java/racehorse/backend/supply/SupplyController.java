package racehorse.backend.supply;

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
 * Flow 4 - vật tư (thức ăn, thuốc, dụng cụ) theo khu vực, bảng SUPPLY_STOCK (V1).
 *
 *  - Club Manager quản lý danh mục: thêm, sửa tên / định mức / khu vực.
 *  - Groom (và Vet, Manager) kiểm đếm: cập nhật số lượng đang có.
 *  - "Đề xuất bổ sung" = số lượng dưới định mức (cột tính sẵn needs_restock), đúng
 *    như thiết kế V1: không có bảng duyệt. Lần kiểm đếm làm một mặt hàng TỤT
 *    xuống dưới định mức thì báo cho mọi Club Manager.
 */
@RestController
@RequestMapping("/api/supplies")
public class SupplyController {

    private final SupplyService service;

    public SupplyController(SupplyService service) {
        this.service = service;
    }

    @GetMapping
    @RequireRole({"CLUB_MANAGER", "HEAD_TRAINER", "VETERINARIAN", "GROOM"})
    public List<SupplyModels.Supply> list(@RequestParam(required = false) String area,
                             @RequestParam(required = false) String category,
                             @RequestParam(defaultValue = "false") boolean lowOnly) {
        return service.list(area, category, lowOnly);
    }

    @PostMapping
    @RequireRole({"CLUB_MANAGER"})
    public ResponseEntity<SupplyModels.Supply> create(@Valid @RequestBody SupplyModels.SupplyRequest req,
                                         @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
                                         HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req, user, http.getRemoteAddr()));
    }

    @PutMapping("/{id}")
    @RequireRole({"CLUB_MANAGER"})
    public SupplyModels.Supply update(@PathVariable int id, @Valid @RequestBody SupplyModels.SupplyRequest req,
                         @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
                         HttpServletRequest http) {
        return service.update(id, req, user, http.getRemoteAddr());
    }

    @PutMapping("/{id}/count")
    @RequireRole({"CLUB_MANAGER", "VETERINARIAN", "GROOM"})
    public SupplyModels.Supply count(@PathVariable int id, @Valid @RequestBody SupplyModels.CountRequest req,
                        @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
                        HttpServletRequest http) {
        return service.count(id, req.currentQty(), user, http.getRemoteAddr());
    }
}
