package racehorse.backend.dto;

import jakarta.validation.constraints.Size;

import racehorse.backend.service.HorseRequestService;

/**
 * Phần học viện điền khi xử lý một yêu cầu.
 *
 * Ngựa vào CLB (ACADEMY): DUYỆT không còn tạo ngựa, mà gửi hợp đồng cho chủ
 * ngựa (V49). Mã đăng ký, chuồng và HLV được chốt lúc NHẬN ngựa, sau khi chủ đã
 * ký và trả tiền (ReceiveRequest). Lúc duyệt Manager chỉ phải xác nhận hai điều
 * (góp ý review "duyệt dựa vào đâu"): ảnh khớp mô tả, và đã đối chiếu giấy tờ
 * cha mẹ. Cả hai ghi vào audit cùng dòng HORSE_REQUEST_APPROVE.
 *
 * Hồ sơ PEDIGREE không ở CLB nên không có hợp đồng: duyệt vẫn cấp mã và tạo
 * hồ sơ ngay như trước, nên registrationCode vẫn còn ở đây.
 *
 * Không kiểm @NotBlank vì cùng record này dùng cho lệnh TỪ CHỐI;
 * {@link HorseRequestService} kiểm đúng nhánh.
 */
// Body của PUT /api/horse-requests/{id}/approve và /reject (chỉ CLUB_MANAGER).
public record ReviewRequest(
        @Size(max = 30, message = "Registration code must be at most 30 characters")
        String registrationCode,

        @Size(max = 20, message = "Stall must be at most 20 characters")
        String stallNo,

        @Size(max = 255, message = "Note must be at most 255 characters")
        String note,

        Integer trainerId,

        Boolean photoChecked,
        Boolean papersChecked
) {
    public ReviewRequest(String registrationCode, String stallNo, String note) {
        this(registrationCode, stallNo, note, null, null, null);
    }

    public ReviewRequest(String registrationCode, String stallNo, String note, Integer trainerId) {
        this(registrationCode, stallNo, note, trainerId, null, null);
    }
}
