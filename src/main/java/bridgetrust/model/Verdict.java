package bridgetrust.model;

public enum Verdict {
  HEALTHY("Healthy", "#7fceb1"),
  QUESTIONABLE("Questionable", "#e6c277"),
  SENSOR_FAULT("Sensor fault", "#ef8c8c"),
  STRUCTURAL("Structural", "#f4a66a");
  public final String label, color;

  Verdict(String label, String color) {
    this.label = label;
    this.color = color;
  }
}
