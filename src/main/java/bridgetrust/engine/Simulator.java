package bridgetrust.engine;

import bridgetrust.model.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Six dedicated sensor threads; one clock captures scenario state atomically for every frame. */
public final class Simulator implements AutoCloseable {
  public static final int PERIOD = 500;

  public enum Scenario {
    NORMAL("Normal traffic", "Healthy traffic and a daily thermal cycle."),
    SPIKE("Sensor spike", "S3 receives an isolated +35 µε offset."),
    STUCK("Frozen sensor", "S2 repeats the exact same raw measurement."),
    DRIFT("Gradual drift", "S4 slowly diverges from its neighbours."),
    DROPOUT("Signal dropout", "S3 stops transmitting; frame deadlines still advance."),
    DAMAGE("Structural shift", "Adjacent S2 and S3 move together by up to 6 µε."),
    THERMAL("Thermal cycle", "An accelerated temperature swing tests compensation."),
    BAD_DATA("Malformed input", "S1 produces NaN; the validation nodes remain alive.");
    public final String title, description;

    Scenario(String title, String description) {
      this.title = title;
      this.description = description;
    }

    @Override
    public String toString() {
      return title;
    }
  }

  private record Mode(Scenario scenario, int start) {}

  private final List<Sensor> sensors = new ArrayList<>();
  private final List<ExecutorService> feeds = new ArrayList<>();
  private final ScheduledExecutorService clock =
      Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "simulation-clock"));
  private final AtomicInteger tick = new AtomicInteger();
  private final BlockingQueue<Reading> queue;
  private volatile Mode mode = new Mode(Scenario.NORMAL, 0);
  private volatile boolean paused;
  private volatile String source = "SIMULATION";
  private volatile String error = "";
  private List<Map<String, Double>> replay = List.of();
  private int replayIndex;
  private double frozen = Double.NaN;

  public Simulator(BlockingQueue<Reading> queue) {
    this.queue = queue;
    for (int i = 0; i < 5; i++) sensors.add(new Sensor.Strain("S" + (i + 1), 42L + i));
    sensors.add(new Sensor.Temperature(47));
    sensors.forEach(
        s -> feeds.add(Executors.newSingleThreadExecutor(r -> new Thread(r, "sensor-" + s.id))));
    clock.scheduleWithFixedDelay(this::emit, 0, PERIOD, TimeUnit.MILLISECONDS);
  }

  public static double truth(int tick) {
    return 24 + 2.4 * Math.sin(tick / 13.0) + 0.8 * Math.sin(tick / 4.5);
  }

  public static double temperature(int tick) {
    return 22 + 7 * Math.sin(tick / 45.0);
  }

  public synchronized void setScenario(Scenario scenario) {
    mode = new Mode(Objects.requireNonNull(scenario), tick.get());
    frozen = Double.NaN;
    replay = List.of();
    replayIndex = 0;
    source = "SIMULATION";
    paused = false;
  }

  public synchronized void replay(List<Map<String, Double>> frames) {
    if (frames.isEmpty()) throw new IllegalArgumentException("Replay contains no frames");
    replay = List.copyOf(frames);
    replayIndex = 0;
    source = "CSV REPLAY";
    paused = false;
  }

  public Scenario scenario() {
    return mode.scenario();
  }

  public boolean paused() {
    return paused;
  }

  public void setPaused(boolean value) {
    paused = value;
  }

  public String source() {
    return source;
  }

  public String error() {
    return error;
  }

  private synchronized void emit() {
    if (paused) return;
    try {
      int t = tick.getAndIncrement();
      Mode captured = mode;
      boolean isReplay = !replay.isEmpty();
      Map<String, Double> replayFrame = isReplay ? replay.get(replayIndex++) : Map.of();
      if (isReplay && replayIndex == replay.size()) {
        paused = true;
        source = "REPLAY COMPLETE";
      }
      int elapsed = t - captured.start();
      double temp =
          captured.scenario() == Scenario.THERMAL ? 22 + 18 * Math.sin(t / 7.0) : temperature(t);
      if (captured.scenario() == Scenario.STUCK && !Double.isFinite(frozen))
        frozen = truth(t) + Sensor.K * (temp - Sensor.TREF);
      double frozenValue = frozen;
      List<Future<?>> jobs = new ArrayList<>();
      for (int i = 0; i < sensors.size(); i++) {
        Sensor sensor = sensors.get(i);
        jobs.add(
            feeds
                .get(i)
                .submit(
                    () -> {
                      if (isReplay && !replayFrame.containsKey(sensor.id)) return;
                      if (!isReplay
                          && captured.scenario() == Scenario.DROPOUT
                          && sensor.id.equals("S3")) return;
                      double value =
                          isReplay ? replayFrame.get(sensor.id) : sensor.measure(truth(t), temp);
                      if (!isReplay)
                        value = inject(captured.scenario(), sensor.id, value, elapsed, frozenValue);
                      try {
                        if (!queue.offer(new Reading(sensor.id, t, value), 1, TimeUnit.SECONDS))
                          error = "Transport queue full; readings dropped";
                      } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                      }
                    }));
      }
      for (Future<?> job : jobs) job.get();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    } catch (Exception ex) {
      error = "Sensor feed failed: " + ex.getMessage();
      paused = true;
    }
  }

  public static double inject(
      Scenario scenario, String id, double value, int elapsed, double frozen) {
    return switch (scenario) {
      case SPIKE -> id.equals("S3") ? value + 35 : value;
      case STUCK -> id.equals("S2") ? frozen : value;
      case DRIFT -> id.equals("S4") ? value + Math.min(12, elapsed * 0.12) : value;
      case DAMAGE ->
          id.equals("S2") || id.equals("S3") ? value + Math.min(6, elapsed * 0.4) : value;
      case BAD_DATA -> id.equals("S1") ? Double.NaN : value;
      default -> value;
    };
  }

  public void close() {
    clock.shutdownNow();
    feeds.forEach(ExecutorService::shutdownNow);
    try {
      clock.awaitTermination(2, TimeUnit.SECONDS);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }
}
