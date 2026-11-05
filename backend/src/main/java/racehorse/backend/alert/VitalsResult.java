package racehorse.backend.alert;

public record VitalsResult(int heartRate, Integer heartRateCeiling, boolean alertRaised,
                           String severity, Integer alertId, String metricType, String message) {

    public VitalsResult(int heartRate, Integer heartRateCeiling, boolean alertRaised,
                        String severity, Integer alertId) {
        this(heartRate, heartRateCeiling, alertRaised, severity, alertId, null, null);
    }
}
