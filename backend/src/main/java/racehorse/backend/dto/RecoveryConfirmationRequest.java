package racehorse.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

import racehorse.backend.service.IncidentGuard;

/**
 * UC18 - những gì bác sĩ thú y khai khi xác nhận một con ngựa đã hồi phục.
 *
 * recoveredInjuryIds phải khai TƯỜNG MINH, không phải "đóng hết những gì còn
 * mở". Bác sĩ liệt kê đúng những chấn thương mình vừa khám và kết luận đã
 * lành; nếu con ngựa còn chấn thương nào khác đang điều trị mà không nằm
 * trong danh sách, UC18 từ chối chứ không tự đóng giúp. Một cú bấm không nên
 * khép được một chấn thương mà người bấm chưa xem.
 *
 * answeredReportIds theo đúng luật đó cho báo cáo sự cố còn mở. Con ngựa có
 * thể có hai báo cáo (vd. một lần Groom dừng buổi tập mà hoá ra bình thường,
 * và một lần chấn thương thật); bác sĩ chỉ khám kèm một báo cáo thì báo cáo
 * kia vẫn mở. Trước đây UC18 vẫn đưa ngựa về ELIGIBLE, nên ngựa "đã hồi phục"
 * mà IncidentGuard vẫn chặn xếp lịch / Start, và báo cáo cũ nằm mãi trong hàng
 * chờ khám. Giờ bác sĩ phải tích những báo cáo lần khám hồi phục này trả lời.
 */
public record RecoveryConfirmationRequest(

        @NotNull(message = "Horse is required")
        Integer horseId,

        /** Những injury_id bác sĩ xác nhận đã lành. Rỗng khi con ngựa đang ở
            diện MONITOR hoặc QUARANTINE mà không có chấn thương nào. */
        List<Integer> recoveredInjuryIds,

        /** Những báo cáo sự cố còn mở mà lần khám hồi phục này trả lời (đóng
            thành EXAMINED). Rỗng / null khi con ngựa không còn báo cáo nào mở. */
        List<Integer> answeredReportIds,

        @NotNull(message = "Examination time is required")
        @PastOrPresent(message = "Examination time cannot be in the future")
        LocalDateTime examDate,

        /** Kết luận khép hồ sơ, ví dụ "Gân trước phải đã lành, vận động bình
            thường". Ghi vào MEDICAL_RECORD.diagnosis của bản ghi xác nhận. */
        @NotBlank(message = "Write the conclusion of the recovery exam")
        @Size(max = 255, message = "Diagnosis must be at most 255 characters")
        String diagnosis,

        String notes
) {}
