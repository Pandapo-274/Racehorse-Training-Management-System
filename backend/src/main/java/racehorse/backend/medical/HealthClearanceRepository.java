package racehorse.backend.medical;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * UC18 - nâng HORSE.status lên ELIGIBLE.
 *
 * Lớp này từng giữ cả INJURY và TRAINING_LOCK, nhưng mỗi bảng đó đã về lớp
 * sở hữu riêng khi có use case thứ hai cùng ghi (InjuryRepository ở UC15,
 * TrainingLockRepository ở UC16). Còn lại đúng một câu, và nó xứng đáng đứng
 * riêng: đây là điểm xếp hàng của toàn bộ UC18.
 */
@Repository
public class HealthClearanceRepository {

    private final JdbcClient jdbc;

    public HealthClearanceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Đặt con ngựa về ELIGIBLE, với điều kiện nó chưa ở ELIGIBLE.
     *
     * Đây là điểm xếp hàng của cả UC18. Hai bác sĩ cùng mở màn xác nhận hồi
     * phục của một con ngựa thì cả hai đều thấy "chưa eligible" ở bước kiểm;
     * chỉ câu UPDATE này phân định được ai thật sự đổi. Người nhận 0 dòng bị
     * huỷ toàn bộ giao dịch, nên không sinh ra hai bản ghi xác nhận cho cùng
     * một lần hồi phục.
     */
    public int clearHorse(int horseId) {
        return jdbc.sql("""
                UPDATE HORSE
                SET status = 'ELIGIBLE', updated_at = SYSUTCDATETIME()
                WHERE horse_id = :id AND status <> 'ELIGIBLE'
                """)
                .param("id", horseId)
                .update();
    }
}
