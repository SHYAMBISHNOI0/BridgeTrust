package bridgetrust.model;

import java.time.Instant;
import java.util.List;

/** Immutable publication: JavaFX never observes a partially processed frame. */
public record Snapshot(
    int tick,
    Instant time,
    List<SensorState> sensors,
    Estimate estimate,
    double plainMean,
    double medianDiscard,
    double temperature,
    double processingMs,
    long rejectedMessages,
    List<Event> events) {
  public Snapshot {
    sensors = List.copyOf(sensors);
    events = List.copyOf(events);
  }

  public record SensorState(
      String id,
      double raw,
      double compensated,
      double trust,
      Verdict verdict,
      double reconstructed,
      String explanation,
      List<Evidence> evidence) {
    public SensorState {
      evidence = List.copyOf(evidence);
    }

    public boolean virtualAvailable() {
      return Double.isFinite(reconstructed);
    }
  }

  public record Estimate(
      double value, double halfWidth, double effectiveSensors, int contributors) {
    public boolean available() {
      return Double.isFinite(value);
    }
  }

  public record Event(Instant time, int tick, String sensor, Verdict verdict, String detail) {}

  public SensorState sensor(String id) {
    return sensors.stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
  }

  public long count(Verdict verdict) {
    return sensors.stream().filter(s -> s.verdict() == verdict).count();
  }
}
