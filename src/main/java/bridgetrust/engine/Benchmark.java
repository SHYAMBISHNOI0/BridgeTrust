package bridgetrust.engine;

import bridgetrust.model.*;
import java.util.*;

/** Reproducible, independent ground truth evaluation. No labels enter the validator. */
public final class Benchmark {
  public record Result(
      String scenario,
      int evaluated,
      int anomalies,
      int detected,
      int correctlyClassified,
      int healthy,
      int falseAlarms,
      double engineRmse,
      double meanRmse,
      double medianRmse) {
    public double recall() {
      return anomalies == 0 ? Double.NaN : detected * 100.0 / anomalies;
    }

    public double accuracy() {
      return anomalies == 0 ? Double.NaN : correctlyClassified * 100.0 / anomalies;
    }

    public double falseAlarmRate() {
      return healthy == 0 ? 0 : falseAlarms * 100.0 / healthy;
    }
  }

  private Benchmark() {}

  public static List<Result> run() {
    List<Result> results = new ArrayList<>();
    for (var scenario : Simulator.Scenario.values()) {
      List<Sensor> sensors = new ArrayList<>();
      for (int i = 0; i < 5; i++) sensors.add(new Sensor.Strain("S" + (i + 1), 100 + i));
      sensors.add(new Sensor.Temperature(105));
      int anomalies = 0, detected = 0, correct = 0, healthy = 0, falseAlarms = 0, frames = 0;
      double err = 0, meanErr = 0, medianErr = 0, frozen = 0;
      try (var validator = new Validator(Map.of(), false)) {
        for (int tick = 0; tick < 200; tick++) {
          int elapsed = tick - 40;
          boolean active = tick >= 40;
          Map<String, Double> raw = new HashMap<>();
          double temp =
              scenario == Simulator.Scenario.THERMAL
                  ? 22 + 18 * Math.sin(tick / 7.0)
                  : Simulator.temperature(tick);
          if (tick == 40) frozen = Simulator.truth(tick) + Sensor.K * (temp - Sensor.TREF);
          for (var sensor : sensors) {
            if (active && scenario == Simulator.Scenario.DROPOUT && sensor.id.equals("S3"))
              continue;
            double value = sensor.measure(Simulator.truth(tick), temp);
            raw.put(
                sensor.id,
                active ? Simulator.inject(scenario, sensor.id, value, elapsed, frozen) : value);
          }
          var snapshot = validator.process(tick, raw, 0);
          if (tick < 65) continue;
          double target =
              Simulator.truth(tick)
                  + (scenario == Simulator.Scenario.DAMAGE
                      ? 2.0 / 5 * Math.min(6, elapsed * 0.4)
                      : 0);
          err += Math.pow(snapshot.estimate().value() - target, 2);
          meanErr += Math.pow(snapshot.plainMean() - target, 2);
          medianErr += Math.pow(snapshot.medianDiscard() - target, 2);
          frames++;
          for (String id : Sensor.STRAIN) {
            Verdict expected = expected(scenario, id), actual = snapshot.sensor(id).verdict();
            if (expected != Verdict.HEALTHY) {
              anomalies++;
              if (actual == Verdict.SENSOR_FAULT || actual == Verdict.STRUCTURAL) detected++;
              if (expected == actual) correct++;
            } else {
              healthy++;
              if (actual == Verdict.SENSOR_FAULT || actual == Verdict.STRUCTURAL) falseAlarms++;
            }
          }
        }
      }
      results.add(
          new Result(
              scenario.title,
              frames,
              anomalies,
              detected,
              correct,
              healthy,
              falseAlarms,
              Math.sqrt(err / frames),
              Math.sqrt(meanErr / frames),
              Math.sqrt(medianErr / frames)));
    }
    return List.copyOf(results);
  }

  private static Verdict expected(Simulator.Scenario scenario, String id) {
    return switch (scenario) {
      case SPIKE, DROPOUT -> id.equals("S3") ? Verdict.SENSOR_FAULT : Verdict.HEALTHY;
      case STUCK -> id.equals("S2") ? Verdict.SENSOR_FAULT : Verdict.HEALTHY;
      case DRIFT -> id.equals("S4") ? Verdict.SENSOR_FAULT : Verdict.HEALTHY;
      case BAD_DATA -> id.equals("S1") ? Verdict.SENSOR_FAULT : Verdict.HEALTHY;
      case DAMAGE -> id.equals("S2") || id.equals("S3") ? Verdict.STRUCTURAL : Verdict.HEALTHY;
      default -> Verdict.HEALTHY;
    };
  }

  public static String csv(List<Result> results) {
    StringBuilder b =
        new StringBuilder(
            "scenario,evaluated_frames,anomalies,detected,correct_classification,healthy_samples,false_alarms,recall_pct,class_accuracy_pct,false_alarm_pct,engine_rmse,mean_rmse,median_rmse\n");
    for (var r : results)
      b.append(
          String.format(
              Locale.ROOT,
              "%s,%d,%d,%d,%d,%d,%d,%.3f,%.3f,%.3f,%.4f,%.4f,%.4f%n",
              r.scenario,
              r.evaluated,
              r.anomalies,
              r.detected,
              r.correctlyClassified,
              r.healthy,
              r.falseAlarms,
              r.recall(),
              r.accuracy(),
              r.falseAlarmRate(),
              r.engineRmse,
              r.meanRmse,
              r.medianRmse));
    return b.toString();
  }
}
