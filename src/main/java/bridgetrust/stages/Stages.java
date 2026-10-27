package bridgetrust.stages;

import static bridgetrust.model.Evidence.Outcome.*;

import bridgetrust.model.Evidence;
import java.util.*;

public final class Stages {
  private Stages() {}

  public static final class Range implements ValidationStage {
    public Evidence check(Context c) {
      boolean temp = c.id().equals("T1");
      boolean valid =
          Double.isFinite(c.raw())
              && c.raw() >= (temp ? -40 : -100)
              && c.raw() <= (temp ? 85 : 100);
      return new Evidence(
          "Range",
          valid ? PASS : FAIL,
          valid
              ? "Within calibrated operating range"
              : "Missing, non-finite or outside calibrated range");
    }
  }

  public static final class Stuck implements ValidationStage {
    public Evidence check(Context c) {
      var h = c.history();
      boolean stuck =
          h.size() >= 5
              && h.subList(h.size() - 5, h.size()).stream()
                  .allMatch(v -> Math.abs(v - c.raw()) < 0.000001);
      return new Evidence(
          "Stuck value",
          stuck ? FAIL : h.size() < 5 ? UNAVAILABLE : PASS,
          stuck
              ? "Identical raw value across 6 frames"
              : h.size() < 5 ? "Collecting 6-frame history" : "Signal continues to vary");
    }
  }

  public static final class Neighbour implements ValidationStage {
    public Evidence check(Context c) {
      boolean suspect = Math.abs(c.residual()) > 1.05;
      return new Evidence(
          "Neighbour",
          !Double.isFinite(c.residual()) ? UNAVAILABLE : suspect ? WATCH : PASS,
          String.format(
              Locale.ROOT,
              "Network residual %+.2f µε; physical adjacency decides classification",
              c.residual()));
    }
  }

  public static final class Drift implements ValidationStage {
    private double positive, negative;

    public Evidence check(Context c) {
      if (!Double.isFinite(c.residual()))
        return new Evidence("Drift", UNAVAILABLE, "No usable residual");
      positive = Math.min(8, Math.max(0, positive + c.residual() - 0.3));
      negative = Math.min(8, Math.max(0, negative - c.residual() - 0.3));
      boolean drift = Math.max(positive, negative) > 3 && Math.abs(c.residual()) > 0.5;
      return new Evidence(
          "Drift",
          drift ? WATCH : PASS,
          String.format(Locale.ROOT, "Two-sided CUSUM %.2f / 3.00", Math.max(positive, negative)));
    }
  }

  public static final class Temperature implements ValidationStage {
    public Evidence check(Context c) {
      return new Evidence(
          "Temperature",
          c.temperatureValid() ? PASS : WATCH,
          c.temperatureValid()
              ? "T1 verified · compensation 0.15 µε/°C at 15°C"
              : "T1 unavailable or invalid; compensated estimate withheld");
    }
  }
}
