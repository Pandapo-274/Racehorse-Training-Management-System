package racehorse.backend.dto;

public record VitalsResult(Integer heartRate, Integer heartRateCeiling, boolean alertRaised,
                           String severity, Integer alertId, String metricType, String message) {

    public VitalsResult(Integer heartRate, Integer heartRateCeiling, boolean alertRaised,
                        String severity, Integer alertId) {
        this(heartRate, heartRateCeiling, alertRaised, severity, alertId, null, null);
    }
}
