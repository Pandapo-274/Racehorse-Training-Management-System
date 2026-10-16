package racehorse.backend.preventive;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import racehorse.backend.auth.ApiException;
import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.horse.HorseRepository;
import racehorse.backend.horse.HorseResponse;
import racehorse.backend.preventive.PreventiveCareRepository.PreventiveCareRow;

/**
 * UC17 - Track Routine Preventive Care (Preventive Care Reminders).
 *
 * Use case này CHỈ ĐỌC. Việc ghi một lần tiêm hay tẩy giun đã là UC14, với
 * record_type = VACCINATION / DEWORMING / FARRIER và nextDueDate bắt buộc -
 * chính ràng buộc ấy là thứ làm cho màn nhắc lịch này chạy được.
 *
 * Không thêm một nút "đánh dấu đã làm" ở đây, dù nghe tiện: đó sẽ là đường
 * ghi thứ hai vào MEDICAL_RECORD, với bộ kiểm riêng của nó, và hai bộ kiểm
 * song song thì sớm muộn cũng lệch nhau. Màn nhắc lịch dẫn người dùng sang
 * form khám của UC14, không tự ghi.
 */
@Service
public class PreventiveCareService {

    /** Mặc định 14 ngày: đủ sớm để đặt lịch bác sĩ, chưa sớm đến mức màn hình
        lúc nào cũng đầy việc chưa cần làm. */
    private static final int DEFAULT_DUE_WITHIN_DAYS = 14;

    private static final List<String> STATUSES =
            List.of("OVERDUE", "NEVER", "DUE_SOON", "UNSCHEDULED", "OK");

    private final PreventiveCareRepository care;
    private final HorseRepository horses;

    public PreventiveCareService(PreventiveCareRepository care, HorseRepository horses) {
        this.care = care;
        this.horses = horses;
    }

    /**
     * @param dueWithinDays bao nhiêu ngày tới thì tính là DUE_SOON
     * @param includeOk     true để xem cả những ô không cần làm gì
     */
    public PreventiveCareBoard board(Integer dueWithinDays, boolean includeOk) {
        int window = (dueWithinDays == null || dueWithinDays < 0)
                ? DEFAULT_DUE_WITHIN_DAYS : dueWithinDays;
        LocalDate today = LocalDate.now();

        List<PreventiveCareItem> all = care.findAll().stream()
                .map(r -> classify(r, today, window))
                .sorted(byUrgency())
                .toList();

        /* Đếm trên TOÀN bộ bảng rồi mới lọc, không phải ngược lại. */
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String s : STATUSES) {
            counts.put(s, 0);
        }
        for (PreventiveCareItem item : all) {
            counts.merge(item.careStatus(), 1, Integer::sum);
        }

        List<PreventiveCareItem> items = includeOk
                ? all
                : all.stream().filter(i -> !"OK".equals(i.careStatus())).toList();

        return new PreventiveCareBoard(counts, items);
    }

    /** Ba ô của một con ngựa, kể cả ô OK - đây là trang hồ sơ, không phải màn nhắc việc. */
    public List<PreventiveCareItem> forHorse(int horseId, Integer dueWithinDays,
                                             AuthenticatedUser user) {
        HorseResponse horse = horses.findById(horseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Horse #" + horseId + " does not exist"));
        if ("HORSE_OWNER".equals(user.role()) && horse.ownerId() != user.userId()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You may not view this horse's records");
        }
        int window = (dueWithinDays == null || dueWithinDays < 0)
                ? DEFAULT_DUE_WITHIN_DAYS : dueWithinDays;
        LocalDate today = LocalDate.now();
        return care.findByHorse(horseId).stream()
                .map(r -> classify(r, today, window))
                .sorted(byUrgency())
                .toList();
    }

    /* ------------------------------------------------------------------ */

    /**
     * Phân loại đặt ở Java chứ không nhúng vào SQL, có chủ đích.
     *
     * "Hôm nay" ở đây là ngày của máy ứng dụng, khác với marked_at hay
     * issued_at vốn phải lấy giờ của database. Hai thứ đó là dấu thời gian
     * lịch sử, sai một giây là hồ sơ xếp sai thứ tự vĩnh viễn; còn "đã quá
     * hạn chưa" là một câu hỏi về lúc đọc màn hình, trả lời lại mỗi lần tải
     * trang. Lẫn hai loại này là nguồn của những lỗi rất khó nhìn ra.
     */
    private static PreventiveCareItem classify(PreventiveCareRow r, LocalDate today, int window) {
        Integer daysUntilDue = r.nextDueDate() == null
                ? null
                : (int) ChronoUnit.DAYS.between(today, r.nextDueDate());

        Integer daysSinceRegistered = r.horseCreatedAt() == null
                ? null
                : (int) ChronoUnit.DAYS.between(r.horseCreatedAt().toLocalDate(), today);

        String status;
        if (r.lastRecordId() == null) {
            status = "NEVER";
        } else if (daysUntilDue == null) {
            /* Đã làm nhưng bản ghi không có ngày hẹn kế tiếp. UC14 bắt buộc
               nextDueDate cho ba loại này, nên chỉ những bản ghi có từ trước
               luật ấy mới rơi vào đây. Vẫn phải có một tên gọi riêng: gộp vào
               OK là màn hình khẳng định con ngựa ổn, trong khi sự thật là
               không ai biết bao giờ phải làm lại. */
            status = "UNSCHEDULED";
        } else if (daysUntilDue < 0) {
            status = "OVERDUE";
        } else if (daysUntilDue <= window) {
            status = "DUE_SOON";
        } else {
            status = "OK";
        }

        return new PreventiveCareItem(
                r.horseId(), r.horseName(), r.registrationCode(), r.stallNo(),
                r.careType(), r.lastRecordId(), r.lastExamDate(), r.nextDueDate(),
                r.vetName(), status, daysUntilDue, daysSinceRegistered);
    }

    /**
     * Quá hạn lâu nhất lên đầu, rồi đến những con chưa bao giờ được làm.
     *
     * NEVER xếp theo số ngày con ngựa đã ở trong chuồng, không phải theo tên.
     * Một con đăng ký hai năm trước mà chưa từng tẩy giun là việc cấp; một con
     * mới nhập tuần trước thì chưa. Cả hai đều mang nhãn NEVER, nên nếu không
     * xếp theo tuổi hồ sơ thì chúng trộn lẫn vào nhau và danh sách dài này
     * không còn nói được gì về thứ tự cần làm.
     */
    private static Comparator<PreventiveCareItem> byUrgency() {
        /* Tách ra biến có kiểu rõ ràng thay vì nối một chuỗi .thenComparing
           dài: chuỗi dài bắt trình biên dịch tự suy kiểu qua nhiều nấc lambda
           và đó là chỗ hay gặp lỗi suy kiểu khó đọc. */
        Comparator<PreventiveCareItem> byStatus =
                Comparator.comparingInt(i -> STATUSES.indexOf(i.careStatus()));
        Comparator<PreventiveCareItem> byScore =
                Comparator.<PreventiveCareItem>comparingInt(PreventiveCareService::urgencyScore)
                        .reversed();
        return byStatus
                .thenComparing(byScore)
                .thenComparing(PreventiveCareItem::horseName,
                               Comparator.nullsLast(Comparator.<String>naturalOrder()))
                .thenComparing(PreventiveCareItem::careType);
    }

    /** Trong cùng một nhóm trạng thái, số càng lớn càng gấp. */
    private static int urgencyScore(PreventiveCareItem i) {
        if ("NEVER".equals(i.careStatus())) {
            return i.daysSinceRegistered() == null ? 0 : i.daysSinceRegistered();
        }
        return i.daysUntilDue() == null ? 0 : -i.daysUntilDue();
    }
}
