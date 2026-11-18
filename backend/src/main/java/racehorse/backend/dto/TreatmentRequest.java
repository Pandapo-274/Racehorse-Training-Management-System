package racehorse.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/*
 * Body của POST /api/treatments (chỉ VETERINARIAN) -> TreatmentService.prescribe.
 *   supplyId có giá trị: thuốc lấy từ kho -> trừ kho (SUPPLY_MOVEMENT) theo quantity, đơn giá
 *                       lấy từ kho; thuốc hết hạn bị từ chối 409.
 *   supplyId null      : thuốc mua ngoài -> nhập medicineName và unitPrice.
 *   requiresConsent    : điều trị lớn -> chờ chủ ngựa đồng ý (WAITING_CONSENT).
 */
public record TreatmentRequest(

        @NotNull(message = "Medical record is required")
        Integer recordId,

        /* V28: Chọn từ kho dược/vật tư */
        Integer supplyId,

        @Size(max = 150, message = "Medicine name must be at most 150 characters")
        String medicineName,

        /* Số lượng sử dụng/kê đơn */
        BigDecimal quantity,

        /* Đơn giá: bắt buộc nếu là thuốc nhập ngoài (supplyId == null) */
        BigDecimal unitPrice,

        /* V28: Yêu cầu chủ ngựa đồng ý trước khi điều trị */
        Boolean requiresConsent,

        @Size(max = 50, message = "Dosage must be at most 50 characters")
        String dosage,

        @Size(max = 50, message = "Frequency must be at most 50 characters")
        String frequency,

        String instruction,

        @NotNull(message = "Start date is required")
        LocalDate startDate,

        LocalDate endDate
) {}
