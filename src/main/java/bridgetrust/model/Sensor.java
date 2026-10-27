package bridgetrust.model;

import java.util.List;
import java.util.Random;

public abstract class Sensor {
  public static final double K = 0.15, TREF = 15;
  public static final List<String> STRAIN = List.of("S1", "S2", "S3", "S4", "S5");
  public static final List<String> IDS = List.of("S1", "S2", "S3", "S4", "S5", "T1");
  public final String id;
  protected final Random random;

  protected Sensor(String id, long seed) {
    this.id = id;
    random = new Random(seed);
  }

  public abstract String type();

  public abstract double measure(double load, double temperature);

  public static final class Strain extends Sensor {
    public Strain(String id, long seed) {
      super(id, seed);
    }

    public String type() {
      return "Strain";
    }

    public double measure(double load, double temperature) {
      return load + K * (temperature - TREF) + random.nextGaussian() * 0.16;
    }
  }

  public static final class Temperature extends Sensor {
    public Temperature(long seed) {
      super("T1", seed);
    }

    public String type() {
      return "Temperature";
    }

    public double measure(double load, double temperature) {
      return temperature + random.nextGaussian() * 0.04;
    }
  }
}
