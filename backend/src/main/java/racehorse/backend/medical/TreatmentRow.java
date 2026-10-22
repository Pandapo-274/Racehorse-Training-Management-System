package racehorse.backend.medical;

import java.time.LocalDate;

/**
 * Một mục điều trị, như màn hình nhìn thấy.
 *
 * Kèm horseId để controller và service kiểm được quyền sở hữu mà không phải
 * đi tra lại MEDICAL_RECORD, và kèm horseName để màn hình nói được "thuốc của
 * Biwa Hayahide" thay vì "thuốc của hồ sơ #12".
 */
public record TreatmentRow(
        int treatmentId,
        int recordId,
        int horseId,
        String horseName,
        String medicineName,
        String dosage,
        String frequency,
        String instruction,
        LocalDate startDate,
        LocalDate endDate,
        /** true khi hôm nay còn nằm trong khoảng điều trị. */
        boolean active
) {}
