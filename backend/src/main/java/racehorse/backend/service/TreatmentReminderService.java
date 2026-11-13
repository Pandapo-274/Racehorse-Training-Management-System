package racehorse.backend.service;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import racehorse.backend.dto.IncidentReportResponse;
import racehorse.backend.entity.Notice;
import racehorse.backend.entity.TreatmentRow;
import racehorse.backend.repository.IncidentRepository;
import racehorse.backend.repository.InjuryRepository;
import racehorse.backend.repository.TreatmentRepository;

/**
 * Handles automated periodic reminders for:
 * 1. Treatment plans completed (end_date reached) whose horses need re-examination
 *    to advance to Light work (notified every 6 hours until advanced).
 * 2. Incident reports in Waiting to be seen due today:
 *    - If assigned/taken by a vet: reminds that specific vet.
 *    - If unassigned: broadcasts reminder to all active veterinarians.
 */
@Service
public class TreatmentReminderService {

    private static final Logger log = LoggerFactory.getLogger(TreatmentReminderService.class);

    private final TreatmentRepository treatments;
    private final InjuryRepository injuries;
    private final IncidentRepository incidents;
    private final NotificationService notifications;

    public TreatmentReminderService(TreatmentRepository treatments,
                                    InjuryRepository injuries,
                                    IncidentRepository incidents,
                                    NotificationService notifications) {
        this.treatments = treatments;
        this.injuries = injuries;
        this.incidents = incidents;
        this.notifications = notifications;
    }

    /**
     * Runs every 6 hours to check for treatments completed and open incident reports.
     */
    @Scheduled(cron = "${app.treatment.reminder.cron:0 0 */6 * * *}",
               zone = "${app.treatment.reminder.zone:Asia/Ho_Chi_Minh}")
    @Transactional
    public void runPeriodicReminders() {
        checkCompletedTreatments();
        checkWaitingIncidentReports();
    }

    /**
     * Checks horses whose treatment plan has completed (end_date <= today).
     * If the horse has not yet progressed to Light work (open injuries are not PARTIAL),
     * notifies all veterinarians every 6 hours until they advance it.
     */
    @Transactional
    public int checkCompletedTreatments() {
        LocalDate today = LocalDate.now();
        List<TreatmentRow> ended = treatments.findRecentlyEnded();
        int count = 0;
        Set<Integer> processedHorses = new HashSet<>();

        for (TreatmentRow t : ended) {
            if (t.endDate() == null || t.endDate().isAfter(today)) {
                continue;
            }
            int horseId = t.horseId();
            if (!processedHorses.add(horseId)) {
                continue;
            }

            // Check if horse still has open injuries not yet marked PARTIAL (i.e. not yet in Light work)
            var openInj = injuries.findOpenInjuries(horseId);
            boolean inLightWork = !openInj.isEmpty() && openInj.stream().allMatch(i -> "PARTIAL".equals(i.status()));
            boolean recovered = openInj.isEmpty();

            if (inLightWork || recovered) {
                // Already advanced to light work or fully healed
                continue;
            }

            // Check if notified in the last 6 hours
            if (!notifications.hasNotificationInLastHours("TREATMENT_COMPLETED_REEXAM", Notice.HORSE, horseId, 6)) {
                String title = "[Treatment Completed] Re-examine " + t.horseName() + " for Light work";
                String message = "The treatment plan for " + t.horseName() + " ended on " + t.endDate()
                        + ". Please examine the horse to advance to Light work rehabilitation.";
                notifications.notifyRole("VETERINARIAN", null,
                        new Notice("TREATMENT_COMPLETED_REEXAM", title, message, Notice.HORSE, horseId));
                count++;
            }
        }
        return count;
    }

    /**
     * Checks incident reports in Waiting to be seen:
     * If scheduled for today/pending:
     * - If taken by a vet -> reminds that vet.
     * - If unassigned -> broadcasts to all active veterinarians.
     */
    @Transactional
    public int checkWaitingIncidentReports() {
        List<IncidentReportResponse> openReports = incidents.search(List.of("PENDING", "RECEIVED"), null, null, null, true, 200);
        int count = 0;

        for (IncidentReportResponse r : openReports) {
            if ("EXAMINED".equals(r.status()) || "DISMISSED".equals(r.status())) {
                continue;
            }
            if (r.receivedBy() != null) {
                // Assigned to a specific vet
                String title = "[Assigned Case] Incident exam needed: " + r.horseName();
                String message = "You have taken incident report #" + r.reportId() + " for " + r.horseName()
                        + " (" + r.symptoms() + "). Please conduct the examination.";
                if (!notifications.hasNotificationSentToday(r.receivedBy(), "INCIDENT_ASSIGNED_REMINDER", Notice.HORSE, r.horseId(), title)) {
                    notifications.notifyUser(r.receivedBy(),
                            new Notice("INCIDENT_ASSIGNED_REMINDER", title, message, Notice.HORSE, r.horseId()));
                    count++;
                }
            } else {
                // Unassigned -> Broadcast to all active veterinarians
                String title = "[Unassigned Case] Incident exam needed: " + r.horseName();
                String message = "Incident report #" + r.reportId() + " for " + r.horseName()
                        + " (" + r.symptoms() + ") has no attending vet. Please take over and examine this case.";
                if (!notifications.hasBroadcastSentToday("INCIDENT_UNASSIGNED_ALERT", Notice.HORSE, r.horseId())) {
                    notifications.notifyRole("VETERINARIAN", null,
                            new Notice("INCIDENT_UNASSIGNED_ALERT", title, message, Notice.HORSE, r.horseId()));
                    count++;
                }
            }
        }
        return count;
    }
}
