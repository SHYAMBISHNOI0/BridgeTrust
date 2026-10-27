package bridgetrust.model;

public record Evidence(String stage, Outcome outcome, String detail) {
  public enum Outcome {
    PASS,
    WATCH,
    FAIL,
    UNAVAILABLE
  }
}
