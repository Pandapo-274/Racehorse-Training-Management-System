package racehorse.backend.service;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import racehorse.backend.dto.DischargeReferralRequest;
import racehorse.backend.dto.ExternalReferralRequest;
import racehorse.backend.dto.ExternalReferralResponse;
import racehorse.backend.dto.HorseResponse;
import racehorse.backend.dto.IncidentReportResponse;
import racehorse.backend.dto.TrainingLockRequest;
import racehorse.backend.entity.Notice;
import racehorse.backend.entity.UserRecord;
import racehorse.backend.exception.ApiException;
import racehorse.backend.repository.ExternalReferralRepository;
import racehorse.backend.repository.HorseRepository;
import racehorse.backend.repository.IncidentRepository;
import racehorse.backend.repository.MedicalRecordRepository;
import racehorse.backend.repository.TrainingLockRepository;
import racehorse.backend.repository.UserRepository;
import racehorse.backend.security.AuthenticatedUser;

@Service
public class ExternalReferralService {

    private final ExternalReferralRepository referrals;
    private final HorseRepository horses;
    private final MedicalRecordRepository medicalRecords;
    private final TrainingLockRepository locks;
    private final UserRepository users;
    private final NotificationService notifications;
    private final RaceRiskNotifier raceRisk;
    private final IncidentRepository incidents;

    public ExternalReferralService(ExternalReferralRepository referrals,
                                   HorseRepository horses,
                                   MedicalRecordRepository medicalRecords,
                                   TrainingLockRepository locks,
                                   UserRepository users,
                                   NotificationService notifications,
                                   RaceRiskNotifier raceRisk,
                                   IncidentRepository incidents) {
        this.referrals = referrals;
        this.horses = horses;
        this.medicalRecords = medicalRecords;
        this.locks = locks;
        this.users = users;
        this.notifications = notifications;
        this.raceRisk = raceRisk;
        this.incidents = incidents;
    }

    @Transactional
    public ExternalReferralResponse referToHospital(ExternalReferralRequest req, AuthenticatedUser vet, String ip) {
        HorseResponse horse = horses.findById(req.horseId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Horse #" + req.horseId() + " does not exist"));

        if (horse.isWithdrawn()) {
            throw new ApiException(HttpStatus.CONFLICT, horse.horseName() + " has left the club");
        }

        referrals.findActiveByHorse(req.horseId()).ifPresent(active -> {
            throw new ApiException(HttpStatus.CONFLICT,
                    horse.horseName() + " is already hospitalized at " + active.hospitalName());
        });

        if (req.reportId() != null) {
            IncidentReportResponse rep = incidents.findById(req.reportId())
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Incident report #" + req.reportId() + " not found"));
            if (rep.horseId() != horse.horseId()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Incident report belongs to a different horse");
            }
        }

        int referralId = referrals.insert(req, vet.userId());

        // 1. Cập nhật trạng thái ngựa thành INJURED
        medicalRecords.updateHorseStatus(horse.horseId(), "INJURED");

        // 2. Kích hoạt FULL training lock để khoá hoàn toàn lịch luyện tập
        var activeLock = locks.findActiveByHorse(horse.horseId());
        if (activeLock.isEmpty()) {
            String lockReason = "Hospitalized: " + req.hospitalName() + " - " + req.referralReason();
            if (lockReason.length() > 255) {
                lockReason = lockReason.substring(0, 252) + "...";
            }
            locks.insert(new TrainingLockRequest(horse.horseId(), null, lockReason, "FULL"), vet.userId());
        } else if ("HEAVY_WORK_ONLY".equals(activeLock.get().lockLevel())) {
            locks.updateLockLevel(horse.horseId(), "FULL");
        }

        // 3. Nếu ca chuyển viện xuất phát từ một incident report, đóng ca báo cáo đó (EXAMINED)
        if (req.reportId() != null) {
            medicalRecords.markReportExamined(req.reportId());
        }

        // 4. Cảnh báo nguy cơ các cuộc đua sắp diễn ra
        raceRisk.warn(horse.horseId(), horse.horseName(), horse.ownerId(), vet,
                "has been transferred to external hospital " + req.hospitalName());

        // 5. Gửi thông báo khẩn cho chủ ngựa, huấn luyện viên trưởng và các bác sĩ
        String vetName = users.findById(vet.userId()).map(UserRecord::fullName).orElse("Veterinarian");

        Notice ownerNotice = new Notice(
                "EXTERNAL_REFERRAL_ISSUED",
                "Hospital referral: " + horse.horseName(),
                horse.horseName() + " has been referred to " + req.hospitalName() + " for specialized treatment ("
                        + req.urgency() + "). Reason: " + req.referralReason() + ". Attending vet: Dr. " + vetName + ".",
                Notice.HORSE, horse.horseId());
        notifications.notifyUser(horse.ownerId(), ownerNotice);

        Notice staffNotice = new Notice(
                "EXTERNAL_REFERRAL_ISSUED",
                "Horse transferred to external hospital: " + horse.horseName(),
                horse.horseName() + " has been referred to " + req.hospitalName() + " (" + req.urgency()
                        + ") by Dr. " + vetName + ". Training has been locked.",
                Notice.HORSE, horse.horseId());
        notifications.notifyHorseTrainer(horse.horseId(), vet, staffNotice);
        notifications.notifyRole("VETERINARIAN", vet, staffNotice);

        users.writeAudit(vet.userId(), "EXTERNAL_REFERRAL", "EXTERNAL_REFERRAL", referralId, "SUCCESS", ip,
                "Hospital: " + req.hospitalName() + " (" + req.urgency() + ")");

        return referrals.findById(referralId)
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Referral record created but could not be read back"));
    }

    @Transactional
    public ExternalReferralResponse discharge(int referralId, AuthenticatedUser vet, DischargeReferralRequest req, String ip) {
        ExternalReferralResponse ref = referrals.findById(referralId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Referral #" + referralId + " not found"));

        if (!"HOSPITALIZED".equals(ref.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "This referral is already closed (" + ref.status() + ")");
        }

        if (referrals.markDischarged(referralId, vet.userId(), req.dischargeNotes()) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Discharge could not be saved or was updated by someone else");
        }

        String postStatus = (req.postDischargeStatus() != null && !req.postDischargeStatus().isBlank())
                ? req.postDischargeStatus()
                : "INJURED";
        medicalRecords.updateHorseStatus(ref.horseId(), postStatus);

        String vetName = users.findById(vet.userId()).map(UserRecord::fullName).orElse("Veterinarian");

        Notice dischargeNotice = new Notice(
                "EXTERNAL_REFERRAL_DISCHARGED",
                "Horse returned from hospital: " + ref.horseName(),
                ref.horseName() + " has returned from " + ref.hospitalName() + " to the club. Discharge notes: "
                        + req.dischargeNotes() + ". Attending vet: Dr. " + vetName + ".",
                Notice.HORSE, ref.horseId());
        notifications.notifyUser(ref.ownerId(), dischargeNotice);
        notifications.notifyHorseTrainer(ref.horseId(), vet, dischargeNotice);
        notifications.notifyRole("VETERINARIAN", vet, dischargeNotice);

        users.writeAudit(vet.userId(), "EXTERNAL_DISCHARGE", "EXTERNAL_REFERRAL", referralId, "SUCCESS", ip,
                "Discharge notes: " + req.dischargeNotes());

        return referrals.findById(referralId)
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Referral record updated but could not be read back"));
    }

    public List<ExternalReferralResponse> list(Integer horseId, String status, int limit, AuthenticatedUser user) {
        if ("HORSE_OWNER".equals(user.role())) {
            if (horseId == null) {
                // Return only owner's horses
                return referrals.search(null, status, limit).stream()
                        .filter(r -> r.ownerId() == user.userId())
                        .toList();
            }
            HorseResponse h = horses.findById(horseId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Horse not found"));
            if (h.ownerId() != user.userId()) {
                throw new ApiException(HttpStatus.FORBIDDEN, "You may only view referrals for your own horses");
            }
        }
        return referrals.search(horseId, status, limit);
    }

    public ExternalReferralResponse get(int referralId, AuthenticatedUser user) {
        ExternalReferralResponse ref = referrals.findById(referralId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Referral #" + referralId + " not found"));
        if ("HORSE_OWNER".equals(user.role()) && ref.ownerId() != user.userId()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You may only view referrals for your own horses");
        }
        return ref;
    }
}
