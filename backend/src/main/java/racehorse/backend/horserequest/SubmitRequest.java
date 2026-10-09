package racehorse.backend.horserequest;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Những gì chủ ngựa khai khi xin đăng ký.
 *
 * Chỉ horseName bắt buộc. Người đang muốn gửi ngựa tới học viện thường chưa
 * cầm đủ giấy tờ trong tay, mà bắt khai đủ thì chặn đúng việc mà biểu mẫu này
 * sinh ra để mở - phần còn thiếu học viện sẽ hỏi lại lúc duyệt.
 *
 * KHÔNG có registrationCode và stallNo: mã đăng ký do học viện cấp, số chuồng
 * do học viện xếp. Nhận hai thứ đó từ chủ ngựa là để người ngoài tự đặt số
 * hiệu trong sổ của học viện.
 */
public record SubmitRequest(
        @NotBlank(message = "Horse name is required")
        @Size(max = 100, message = "Horse name must be at most 100 characters")
        String horseName,

        @Size(max = 50, message = "Breed must be at most 50 characters")
        String breed,

        @Pattern(regexp = "COLT|FILLY|STALLION|MARE|GELDING",
                 message = "Sex must be COLT, FILLY, STALLION, MARE or GELDING")
        String horseGender,

        @PastOrPresent(message = "Date of birth cannot be in the future")
        LocalDate dateOfBirth,

        @Size(max = 50, message = "Colour must be at most 50 characters")
        String color,

        @Size(max = 500, message = "Note must be at most 500 characters")
        String note
) {}
