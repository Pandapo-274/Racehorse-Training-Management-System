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

import racehorse.backend.entity.SupplyModels;
import racehorse.backend.security.AuthInterceptor;
import racehorse.backend.security.AuthenticatedUser;
import racehorse.backend.security.RequireRole;
import racehorse.backend.service.SupplyService;

/**
 * Flow 4 - vật tư (thức ăn, thuốc, dụng cụ) theo khu vực, bảng SUPPLY_STOCK (V1).
 *
 *  - Club Manager quản lý kho: thêm, sửa danh mục; kiểm đếm, nhập hàng, huỷ hàng;
 *    xem sổ kho (Head Trainer cũng xem được sổ).
 *  - Groom kiểm đếm thức ăn và dụng cụ (không đếm thuốc).
 *  - Veterinarian chỉ XEM thuốc (GET, frontend lọc MEDICINE) để biết còn gì mà
 *    kê đơn; không đếm, nhập, huỷ: bác sĩ không quản lý kho (đặc tả: kho là
 *    danh mục của Club Manager). Kê đơn vẫn tự trừ kho thuốc (TreatmentService).
 *  - "Đề xuất bổ sung" = số lượng dưới định mức (cột tính sẵn needs_restock), đúng
 *    như thiết kế V1: không có bảng duyệt. Lần kiểm đếm làm một mặt hàng TỤT
 *    xuống dưới định mức thì báo cho mọi Club Manager (thuốc: cả bác sĩ).
 *  - Groom chủ động báo một món (sắp hết, hỏng, hết hạn): POST /{id}/report,
 *    chuông cho Manager (thuốc: cả bác sĩ). Không đổi số lượng.
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
    @RequireRole({"CLUB_MANAGER", "GROOM"})
    public SupplyModels.Supply count(@PathVariable int id, @Valid @RequestBody SupplyModels.CountRequest req,
                        @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
                        HttpServletRequest http) {
        return service.count(id, req.currentQty(), req.reason(), req.note(), user, http.getRemoteAddr());
    }

    /** A delivery arrived (club manager): adds to the quantity and writes a RECEIVE line (V21). */
    @PostMapping("/{id}/receive")
    @RequireRole({"CLUB_MANAGER"})
    public SupplyModels.Supply receive(@PathVariable int id, @Valid @RequestBody SupplyModels.ReceiveRequest req,
                                       @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
                                       HttpServletRequest http) {
        return service.receive(id, req, user, http.getRemoteAddr());
    }

    /** Thrown away (spoiled, expired, broken): takes from the quantity and writes a WASTE line. */
    @PostMapping("/{id}/waste")
    @RequireRole({"CLUB_MANAGER"})
    public SupplyModels.Supply waste(@PathVariable int id, @Valid @RequestBody SupplyModels.WasteRequest req,
                                     @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
                                     HttpServletRequest http) {
        return service.waste(id, req, user, http.getRemoteAddr());
    }

    /**
     * Flag an item to the club manager (and the vets, for medicine): running low,
     * damaged, expired. The groom's "propose a restock"; the quantity is not changed.
     */
    @PostMapping("/{id}/report")
    @RequireRole({"GROOM", "HEAD_TRAINER", "VETERINARIAN"})
    public java.util.Map<String, String> report(@PathVariable int id,
                                                @Valid @RequestBody SupplyModels.ProblemReport req,
                                                @RequestAttribute(AuthInterceptor.USER_ATTR) AuthenticatedUser user,
                                                HttpServletRequest http) {
        return java.util.Map.of("message", service.report(id, req, user, http.getRemoteAddr()));
    }

    /** The last 100 movements of one item, newest first. */
    @GetMapping("/{id}/movements")
    @RequireRole({"CLUB_MANAGER", "HEAD_TRAINER"})
    public List<SupplyModels.Movement> movements(@PathVariable int id) {
        return service.movements(id);
    }
}
