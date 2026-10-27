package bridgetrust.model;

/** Immutable transport message. NaN denotes malformed input, not a missing frame. */
public record Reading(String id, int tick, double value) {
  public Reading {
    if (!Sensor.IDS.contains(id) || tick < 0)
      throw new IllegalArgumentException("Unknown sensor or negative tick");
  }
}
