package racehorse.backend.dashboard;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * UC6 - View Master Fitness & Training Dashboard.
 *
 * Một endpoint duy nhất trả toàn bộ màn hình. Tách thành 5 endpoint riêng thì
 * frontend phải gọi 5 lần và tự ghép, trong khi màn này luôn hiện cả 5 khối
 * cùng lúc - không có khối nào tải riêng lẻ.
 *
 * Chưa có kiểm tra quyền. Khi nhóm thêm Spring Security, màn này chỉ dành cho
 * HEAD_TRAINER và CLUB_MANAGER - gắn @PreAuthorize vào đây.
 */
@RestController
@RequestMapping("/api/trainer/dashboard")
public class TrainerDashboardController {

    private static final int DEFAULT_WEEKS = 8;
    private static final int MAX_WEEKS = 52;

    private final TrainerDashboardService service;

    public TrainerDashboardController(TrainerDashboardService service) {
        this.service = service;
    }

    /**
     * @param weeks số tuần hiển thị trên biểu đồ thể lực, mặc định 8.
     *              Cắt biên vào [1, 52] để một tham số bịa trên URL không làm
     *              query quét toàn bộ bảng.
     */
    @GetMapping
    public TrainerDashboard.Response get(
            @RequestParam(defaultValue = "" + DEFAULT_WEEKS) int weeks) {
        int safeWeeks = Math.max(1, Math.min(MAX_WEEKS, weeks));
        return service.load(safeWeeks);
    }
}
