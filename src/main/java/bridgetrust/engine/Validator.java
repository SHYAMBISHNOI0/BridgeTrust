package bridgetrust.engine;

import static bridgetrust.model.Evidence.Outcome.*;

import bridgetrust.model.*;
import bridgetrust.model.Snapshot.*;
import bridgetrust.stages.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Frame-ordered state machine; two validation nodes work on disjoint sensor histories. */
public final class Validator implements AutoCloseable {
  private final Map<String, Double> trust = new HashMap<>();
  private final Map<String, List<Double>> history = new HashMap<>();
  private final Map<String, List<ValidationStage>> pipelines = new HashMap<>();
  private final Map<String, Verdict> previous = new HashMap<>(), candidate = new HashMap<>();
  private final CouplingGraph graph = new CouplingGraph();
  private final ExecutorService[] nodes;
  private final Deque<Event> events = new ArrayDeque<>();

  public Validator(Map<String, Double> restored, boolean concurrent) {
    for (String id : Sensor.IDS) {
      double t = restored.getOrDefault(id, 1.0);
      trust.put(id, Double.isFinite(t) && t >= 0 && t <= 1 ? t : 1.0);
      history.put(id, new ArrayList<>());
      pipelines.put(
          id,
          id.equals("T1")
              ? List.of(new Stages.Range(), new Stages.Stuck())
              : List.of(
                  new Stages.Range(),
                  new Stages.Stuck(),
                  new Stages.Neighbour(),
                  new Stages.Drift(),
                  new Stages.Temperature()));
    }
    nodes =
        concurrent
            ? new ExecutorService[] {
              Executors.newSingleThreadExecutor(r -> new Thread(r, "validation-node-west")),
              Executors.newSingleThreadExecutor(r -> new Thread(r, "validation-node-east"))
            }
            : null;
  }

  public synchronized Map<String, Double> trust() {
    return Map.copyOf(trust);
  }

  public synchronized Snapshot process(int tick, Map<String, Double> raw, long rejected) {
    long start = System.nanoTime();
    Instant now = Instant.now();
    double temperature = raw.getOrDefault("T1", Double.NaN);
    var temperatureEvidence = validate("T1", temperature, 0, true);
    boolean tempValid = temperatureEvidence.stream().noneMatch(e -> e.outcome() == FAIL);
    Map<String, Double> compensated = new LinkedHashMap<>();
    for (String id : Sensor.STRAIN) {
      double value = raw.getOrDefault(id, Double.NaN);
      if (Double.isFinite(value) && value >= -100 && value <= 100)
        compensated.put(id, value - (tempValid ? Sensor.K * (temperature - Sensor.TREF) : 0));
    }
    double median = Fusion.median(new ArrayList<>(compensated.values()));
    Map<String, Double> residuals = new HashMap<>();
    compensated.forEach((id, value) -> residuals.put(id, value - median));
    Map<String, List<Evidence>> evidence = new LinkedHashMap<>();
    List<Future<List<Evidence>>> futures = new ArrayList<>();
    for (int i = 0; i < Sensor.STRAIN.size(); i++) {
      String id = Sensor.STRAIN.get(i);
      Callable<List<Evidence>> task =
          () ->
              validate(
                  id,
                  raw.getOrDefault(id, Double.NaN),
                  residuals.getOrDefault(id, Double.NaN),
                  tempValid);
      if (nodes == null) {
        try {
          evidence.put(id, task.call());
        } catch (Exception ex) {
          throw new IllegalStateException(ex);
        }
      } else futures.add(nodes[i < 3 ? 0 : 1].submit(task));
    }
    if (nodes != null) {
      try {
        for (int i = 0; i < futures.size(); i++)
          evidence.put(Sensor.STRAIN.get(i), futures.get(i).get());
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Validation interrupted", ex);
      } catch (ExecutionException ex) {
        throw new IllegalStateException("Validation node failed", ex.getCause());
      }
    }
    evidence.put("T1", temperatureEvidence);
    Set<String> hard = new HashSet<>(), suspect = new HashSet<>();
    evidence.forEach(
        (id, checks) -> {
          if (checks.stream().anyMatch(e -> e.outcome() == FAIL)) hard.add(id);
          if (checks.stream()
              .anyMatch(e -> e.outcome() == WATCH && !e.stage().equals("Temperature")))
            suspect.add(id);
        });
    Map<String, Verdict> verdicts = new HashMap<>();
    Map<String, String> explanations = new HashMap<>();
    for (String id : Sensor.IDS) {
      Verdict verdict = Verdict.HEALTHY;
      String explanation =
          id.equals("T1")
              ? "Verified thermal reference. Used for compensation, not strain fusion."
              : "All available checks pass. Reading contributes to the estimate.";
      if (hard.contains(id)) {
        verdict = Verdict.SENSOR_FAULT;
        explanation =
            evidence.get(id).stream()
                .filter(e -> e.outcome() == FAIL)
                .map(Evidence::detail)
                .findFirst()
                .orElseThrow();
        candidate.remove(id);
      } else if (suspect.contains(id)) {
        Verdict proposed =
            graph.coherent(id, residuals, hard) ? Verdict.STRUCTURAL : Verdict.SENSOR_FAULT;
        verdict = proposed == candidate.get(id) ? proposed : Verdict.QUESTIONABLE;
        candidate.put(id, proposed);
        explanation =
            verdict == Verdict.QUESTIONABLE
                ? "Deviation detected; waiting for a second consistent frame."
                : proposed == Verdict.STRUCTURAL
                    ? "Adjacent sensors shift in the same direction. Preserve measurement;"
                        + " investigate the structure."
                    : "Isolated deviation; physical neighbours do not confirm the shift. Excluded"
                        + " from fusion.";
      } else {
        candidate.remove(id);
        if (!id.equals("T1") && !tempValid) {
          verdict = Verdict.QUESTIONABLE;
          explanation = "Temperature reference failed. Compensated estimate is unavailable.";
        }
      }
      double score = trust.get(id);
      score =
          verdict == Verdict.SENSOR_FAULT
              ? score * 0.72
              : verdict == Verdict.QUESTIONABLE ? score : score + 0.12 * (1 - score);
      trust.put(id, score);
      if (verdict == Verdict.HEALTHY && score < 0.8) {
        verdict = Verdict.QUESTIONABLE;
        explanation = "Checks pass; trust is recovering after a fault.";
      }
      verdicts.put(id, verdict);
      explanations.put(id, explanation);
    }
    Map<String, Double> usable = new HashMap<>();
    List<Double> xs = new ArrayList<>(), weights = new ArrayList<>();
    for (String id : Sensor.STRAIN) {
      boolean accepted =
          verdicts.get(id) != Verdict.SENSOR_FAULT && !suspect.contains(id)
              || verdicts.get(id) == Verdict.STRUCTURAL;
      if (tempValid && compensated.containsKey(id) && accepted) {
        usable.put(id, compensated.get(id));
        xs.add(compensated.get(id));
        weights.add(trust.get(id));
      }
    }
    List<SensorState> states = new ArrayList<>();
    for (String id : Sensor.IDS) {
      var verdict = verdicts.get(id);
      String detail = explanations.get(id);
      double virtual =
          verdict == Verdict.SENSOR_FAULT && tempValid ? graph.reconstruct(id, usable) : Double.NaN;
      states.add(
          new SensorState(
              id,
              raw.getOrDefault(id, Double.NaN),
              id.equals("T1")
                  ? temperature
                  : tempValid ? compensated.getOrDefault(id, Double.NaN) : Double.NaN,
              trust.get(id),
              verdict,
              virtual,
              detail,
              evidence.get(id)));
      if (verdict != previous.getOrDefault(id, Verdict.HEALTHY)) {
        events.addFirst(new Event(now, tick, id, verdict, detail));
        while (events.size() > 200) events.removeLast();
      }
      previous.put(id, verdict);
    }
    var values = new ArrayList<>(compensated.values());
    double mean =
        tempValid
            ? values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN)
            : Double.NaN;
    return new Snapshot(
        tick,
        now,
        states,
        Fusion.weighted(xs, weights),
        mean,
        tempValid ? Fusion.medianDiscard(values) : Double.NaN,
        temperature,
        (System.nanoTime() - start) / 1_000_000.0,
        rejected,
        List.copyOf(events));
  }

  private List<Evidence> validate(
      String id, double raw, double residual, boolean temperatureValid) {
    List<Double> h = history.get(id);
    var context = new ValidationStage.Context(id, raw, residual, temperatureValid, List.copyOf(h));
    var checks = pipelines.get(id).stream().map(stage -> stage.check(context)).toList();
    if (Double.isFinite(raw)) {
      h.add(raw);
      if (h.size() > 12) h.remove(0);
    } else h.clear();
    return checks;
  }

  public void close() {
    if (nodes != null) for (ExecutorService node : nodes) node.shutdownNow();
  }
}
