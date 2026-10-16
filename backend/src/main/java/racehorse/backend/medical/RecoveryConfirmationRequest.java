package racehorse.backend.medical;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

/**
 * UC18 - những gì bác sĩ thú y khai khi xác nhận một con ngựa đã hồi phục.
 *
 * recoveredInjuryIds phải khai TƯỜNG MINH, không phải "đóng hết những gì còn
 * mở". Bác sĩ liệt kê đúng những chấn thương mình vừa khám và kết luận đã
 * lành; nếu con ngựa còn chấn thương nào khác đang điều trị mà không nằm
 * trong danh sách, UC18 từ chối chứ không tự đóng giúp. Một cú bấm không nên
 * khép được một chấn thương mà người bấm chưa xem.
 */
public record RecoveryConfirmationRequest(

        @NotNull(message = "Horse is required")
        Integer horseId,

        /** Những injury_id bác sĩ xác nhận đã lành. Rỗng khi con ngựa đang ở
            diện MONITOR hoặc QUARANTINE mà không có chấn thương nào. */
        List<Integer> recoveredInjuryIds,

        @NotNull(message = "Examination time is required")
        @PastOrPresent(message = "Examination time cannot be in the future")
        LocalDateTime examDate,

        /** Kết luận khép hồ sơ, ví dụ "Gân trước phải đã lành, vận động bình
            thường". Ghi vào MEDICAL_RECORD.diagnosis của bản ghi xác nhận. */
        @Size(max = 255, message = "Diagnosis must be at most 255 characters")
        String diagnosis,

        String notes
) {}
