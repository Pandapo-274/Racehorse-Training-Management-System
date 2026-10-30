package racehorse.backend.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Dữ liệu trang "Club overview" của Club Manager (GET /api/manager/dashboard).
 *
 * Đề bài: báo cáo tổng quan về hiệu suất huấn luyện, chi phí vận hành chuồng
 * trại và doanh thu giải đấu. Trước đây trang này chỉ có số liệu viết cứng.
 */
public final class ManagerDashboard {

    private ManagerDashboard() {
    }

    /**
     * horses: ngựa của club còn ở học viện (ACADEMY, ACTIVE).
     * quarterCost: tổng EXPENSE_LOG trong quý hiện tại. seasonPrize: tổng tiền
     * thưởng các cuộc đua trong năm hiện tại.
     */
    public record Summary(int horses, int activePlans, int lockedHorses,
                          BigDecimal quarterCost, String quarterLabel,
                          BigDecimal seasonPrize, int season) {
    }

    /** Một tuần (bắt đầu thứ Hai): tổng cự ly các buổi tập đã chạy. Tuần không có buổi nào vẫn có mặt, giá trị 0. */
    public record WorkloadPoint(LocalDate weekStart, String label, BigDecimal km, int sessions) {
    }

    public record HealthCount(String status, int count) {
    }

    /**
     * Một việc đang chờ ai đó xử lý. kind: REGISTRATION, WITHDRAWAL, SUPPLY,
     * INCIDENT, ALERT. Backend không trả đường dẫn frontend (cùng lý do với
     * NOTIFICATION); frontend tự đổi kind ra trang cần mở.
     * createdAt là giờ UTC, giống mọi cột thời gian khác.
     */
    public record OpenItem(String kind, String item, String role, String horseName,
                           LocalDateTime createdAt, String status) {
    }

    /** openItemCount là tổng số việc đang chờ; openItems chỉ là những việc đầu danh sách. */
    public record Response(Summary summary, List<WorkloadPoint> workload, List<HealthCount> health,
                           List<OpenItem> openItems, int openItemCount) {
    }
}
