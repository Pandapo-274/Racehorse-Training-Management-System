package racehorse.backend.alert;

public record VitalsResult(int heartRate, Integer heartRateCeiling, boolean alertRaised,
                           String severity, Integer alertId) {}
