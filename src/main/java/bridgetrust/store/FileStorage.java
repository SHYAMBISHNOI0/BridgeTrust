package bridgetrust.store;

import bridgetrust.model.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Single writer, RFC4180 escaping, filtered serialization and atomic trust replacement. */
public final class FileStorage implements Storage {
  private final Path root, session;
  private final BufferedWriter readings, estimates, events;
  private int lastEventTick = -1;

  public FileStorage(Path root, String sessionId) throws IOException {
    this.root = root;
    session = root.resolve("sessions").resolve(sessionId);
    Files.createDirectories(session);
    readings =
        writer(
            "readings.csv", "tick,sensor,value,compensated,trust,verdict,reconstructed,evidence");
    estimates =
        writer(
            "estimates.csv",
            "tick,time,estimate,ci95_half_width,effective_sensors,contributors,plain_mean,median_discard,processing_ms");
    events = writer("events.csv", "time,tick,sensor,verdict,evidence");
  }

  private BufferedWriter writer(String name, String header) throws IOException {
    var writer =
        Files.newBufferedWriter(
            session.resolve(name), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    writer.write(header);
    writer.newLine();
    return writer;
  }

  public synchronized void save(Snapshot s, Map<String, Double> trust) {
    try {
      for (var r : s.sensors()) {
        String checks =
            r.evidence().stream()
                .map(e -> e.stage() + ":" + e.outcome() + ":" + e.detail())
                .reduce((a, b) -> a + " | " + b)
                .orElse("");
        readings.write(
            s.tick()
                + ","
                + r.id()
                + ","
                + number(r.raw())
                + ","
                + number(r.compensated())
                + ","
                + r.trust()
                + ","
                + r.verdict()
                + ","
                + number(r.reconstructed())
                + ","
                + csv(checks));
        readings.newLine();
      }
      var e = s.estimate();
      estimates.write(
          s.tick()
              + ","
              + s.time()
              + ","
              + number(e.value())
              + ","
              + number(e.halfWidth())
              + ","
              + e.effectiveSensors()
              + ","
              + e.contributors()
              + ","
              + number(s.plainMean())
              + ","
              + number(s.medianDiscard())
              + ","
              + s.processingMs());
      estimates.newLine();
      for (var event : s.events())
        if (event.tick() > lastEventTick) {
          events.write(
              event.time()
                  + ","
                  + event.tick()
                  + ","
                  + event.sensor()
                  + ","
                  + event.verdict()
                  + ","
                  + csv(event.detail()));
          events.newLine();
        }
      lastEventTick = s.tick();
      readings.flush();
      estimates.flush();
      events.flush();
      saveTrust(trust);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  public static String csv(String value) {
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }

  public static String number(double value) {
    return Double.isFinite(value) ? Double.toString(value) : "";
  }

  private void saveTrust(Map<String, Double> trust) throws IOException {
    Path temporary = root.resolve("trust-v2.ser.tmp"), target = root.resolve("trust-v2.ser");
    try (var output = new ObjectOutputStream(Files.newOutputStream(temporary))) {
      output.writeObject(new HashMap<>(trust));
    }
    try {
      Files.move(
          temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException ex) {
      Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  public synchronized Map<String, Double> loadTrust() {
    Path path = root.resolve("trust-v2.ser");
    if (!Files.exists(path)) return Map.of();
    try (var input = new ObjectInputStream(Files.newInputStream(path))) {
      input.setObjectInputFilter(
          ObjectInputFilter.Config.createFilter(
              "maxdepth=5;maxrefs=100;maxbytes=8192;java.util.HashMap;java.util.Map$Entry;java.lang.String;java.lang.Double;java.lang.Number;!*"));
      Object object = input.readObject();
      Map<String, Double> result = new HashMap<>();
      if (object instanceof Map<?, ?> map)
        map.forEach(
            (k, v) -> {
              if (k instanceof String id
                  && Sensor.IDS.contains(id)
                  && v instanceof Double d
                  && Double.isFinite(d)
                  && d >= 0
                  && d <= 1) result.put(id, d);
            });
      return result;
    } catch (IOException | ClassNotFoundException ex) {
      System.err.println(
          "Trust checkpoint rejected; starting fresh: " + ex.getClass().getSimpleName());
      return Map.of();
    }
  }

  public String status() {
    return "Local audit active";
  }

  public Path sessionDirectory() {
    return session;
  }

  public synchronized void close() {
    for (var writer : List.of(readings, estimates, events))
      try {
        writer.close();
      } catch (IOException ex) {
        System.err.println("Audit close failed: " + ex.getMessage());
      }
  }
}
