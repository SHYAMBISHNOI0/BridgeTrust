package bridgetrust.engine;

import bridgetrust.model.*;
import bridgetrust.store.Storage;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded producer/consumer transport with duplicate rejection and partial-frame deadlines. */
public final class Engine implements AutoCloseable {
  private record Pending(long created, Map<String, Double> values) {}

  private final BlockingQueue<Reading> queue = new ArrayBlockingQueue<>(512);
  private final AtomicReference<Snapshot> latest = new AtomicReference<>();
  private final Deque<Snapshot> history = new ArrayDeque<>();
  private final Validator validator;
  private final Storage store;
  private final Thread worker;
  private volatile boolean running = true;
  private volatile String error = "";
  private long rejected;

  public Engine(Storage store) {
    this.store = store;
    validator = new Validator(store.loadTrust(), true);
    worker = new Thread(this::run, "frame-coordinator");
    worker.start();
  }

  public BlockingQueue<Reading> queue() {
    return queue;
  }

  public Snapshot latest() {
    return latest.get();
  }

  public String error() {
    return error;
  }

  public synchronized List<Snapshot> history() {
    return List.copyOf(history);
  }

  private void run() {
    TreeMap<Integer, Pending> pending = new TreeMap<>();
    int completed = -1;
    try {
      while (running) {
        Reading reading = queue.poll(40, TimeUnit.MILLISECONDS);
        if (reading != null) {
          if (reading.tick() <= completed) rejected++;
          else {
            Pending frame =
                pending.computeIfAbsent(
                    reading.tick(), k -> new Pending(System.nanoTime(), new LinkedHashMap<>()));
            if (frame.values().putIfAbsent(reading.id(), reading.value()) != null) rejected++;
          }
        }
        while (!pending.isEmpty()) {
          var first = pending.firstEntry();
          Pending frame = first.getValue();
          if (frame.values().size() < Sensor.IDS.size()
              && System.nanoTime() - frame.created() < 750_000_000L
              && pending.size() < 8) break;
          pending.pollFirstEntry();
          completed = first.getKey();
          Snapshot snapshot = validator.process(completed, frame.values(), rejected);
          store.save(snapshot, validator.trust());
          synchronized (this) {
            history.addLast(snapshot);
            while (history.size() > 360) history.removeFirst();
          }
          latest.set(snapshot);
        }
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException ex) {
      error = "Processing stopped: " + ex.getMessage();
      ex.printStackTrace();
    }
  }

  public void close() {
    running = false;
    worker.interrupt();
    try {
      worker.join(3000);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
    validator.close();
    store.close();
  }
}
