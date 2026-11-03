package racehorse.backend.race;

import java.util.List;

import org.springframework.stereotype.Component;

import racehorse.backend.auth.AuthenticatedUser;
import racehorse.backend.notification.Notice;
import racehorse.backend.notification.NotificationService;

/**
 * Warns everyone involved when something happens to a horse that has an
 * UPCOMING race entry.
 *
 * Registration checks fitness only once, on the day the entry is made. Anything
 * that happens between that day and the race - a training lock, an exam that
 * moves the horse off ELIGIBLE, an incident report - used to leave the entry
 * UPCOMING with nobody told. This is the one place that connects those events
 * to race entries.
 *
 * Who is told:
 * - the owner and every Head Trainer (notifyHorseWatchers, actor excluded);
 * - the person who caused the event, as a separate notice. A vet issuing a lock
 *   could not see that the horse had a race; now the vet gets the race name and
 *   date right away.
 *
 * It does NOT scratch the entry. Whether the horse can still run is a decision
 * for the Head Trainer and the vet, made with the race date in front of them.
 */
@Component
public class RaceRiskNotifier {

    private final RaceRepository races;
    private final NotificationService notifications;

    public RaceRiskNotifier(RaceRepository races, NotificationService notifications) {
        this.races = races;
        this.notifications = notifications;
    }

    /**
     * @param what what just happened, written to follow "but": e.g.
     *             "has just been locked from training (FULL)".
     */
    public void warn(int horseId, String horseName, int ownerId, AuthenticatedUser actor, String what) {
        List<RaceModels.Entry> upcoming = races.find(null, horseId, null).stream()
                .filter(e -> "UPCOMING".equals(e.state()))
                .toList();
        for (RaceModels.Entry e : upcoming) {
            String message = clip(horseName + " is entered in " + e.raceName() + " on " + e.raceDate()
                    + " but " + what + ". Decide whether to scratch it.");
            Notice notice = new Notice("RACE_AT_RISK", "Race entry at risk", message, Notice.HORSE, horseId);
            notifications.notifyHorseWatchers(ownerId, actor, notice);
            if (actor != null) {
                notifications.notifyUser(actor.userId(), notice);
            }
        }
    }

    private static String clip(String s) {
        return s.length() <= 500 ? s : s.substring(0, 497) + "...";
    }
}
