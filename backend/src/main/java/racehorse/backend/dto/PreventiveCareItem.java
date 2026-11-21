package racehorse.backend.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

import racehorse.backend.repository.PreventiveCareRepository;

/**
 * Một ô trong bảng nhắc lịch: một con ngựa × một loại chăm sóc định kỳ.
 *
 * Mỗi con ngựa sinh ra ba ô (VACCINATION, DEWORMING, FARRIER) dù nó chưa từng
 * được làm loại nào - xem ghi chú ở PreventiveCareRepository về lý do.
 *
 * lastRecordId null nghĩa là chưa từng làm. Khi đó lastExamDate, nextDueDate
 * và vetName đều null, còn daysSinceRegistered cho biết con ngựa đã ở trong
 * chuồng bao lâu mà chưa ai làm - đó là thước đo mức cấp thiết thay cho ngày
 * hẹn mà nó không có.
 */
/*
 * careStatus:
 *   NEVER       - chưa từng làm loại chăm sóc này
 *   UNSCHEDULED - đã làm nhưng không có ngày hẹn lần sau (next_due_date NULL)
 *   OVERDUE     - đã quá ngày hẹn
 *   DUE_SOON    - sắp đến hạn (trong cửa sổ N ngày, mặc định 14)
 *   OK          - còn xa
 */
public record PreventiveCareItem(
        int horseId,
        int ownerId,
        String horseName,
        String registrationCode,
        String stallNo,
        String careType,
        Integer lastRecordId,
        LocalDateTime lastExamDate,
        LocalDate nextDueDate,
        String vetName,
        /** NEVER, UNSCHEDULED, OVERDUE, DUE_SOON hoặc OK. */
        String careStatus,
        /** Âm là đã quá hạn bấy nhiêu ngày. Null khi không có ngày hẹn. */
        Integer daysUntilDue,
        Integer daysSinceRegistered
) {}
