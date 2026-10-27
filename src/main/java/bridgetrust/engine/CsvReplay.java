package bridgetrust.engine;

import bridgetrust.model.Sensor;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class CsvReplay {
  private CsvReplay() {}

  /** Strict, bounded input. Blank values represent missing samples; NaN tests malformed input. */
  public static List<Map<String, Double>> read(Path path) throws IOException {
    if (Files.size(path) > 10_000_000) throw new IOException("Replay exceeds the 10 MB limit");
    TreeMap<Integer, Map<String, Double>> frames = new TreeMap<>();
    try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      if (!"tick,sensor,value".equals(reader.readLine()))
        throw new IOException("Expected CSV header: tick,sensor,value");
      String line;
      int number = 1, previousTick = -1;
      Set<String> seen = new HashSet<>();
      while ((line = reader.readLine()) != null) {
        number++;
        if (line.isBlank()) continue;
        String[] fields = line.split(",", -1);
        try {
          if (fields.length != 3) throw new IllegalArgumentException();
          int tick = Integer.parseInt(fields[0]);
          String id = fields[1];
          if (tick < 0
              || tick < previousTick
              || !Sensor.IDS.contains(id)
              || !seen.add(tick + ":" + id)) throw new IllegalArgumentException();
          previousTick = tick;
          Map<String, Double> frame = frames.computeIfAbsent(tick, k -> new HashMap<>());
          if (!fields[2].isBlank()) frame.put(id, Double.parseDouble(fields[2]));
          if (frames.size() > 20_000) throw new IOException("Replay exceeds 20,000 frames");
        } catch (IllegalArgumentException ex) {
          throw new MalformedReadingException(
              "Invalid, unordered or duplicate reading at line " + number);
        }
      }
    }
    if (frames.isEmpty()) throw new IOException("Replay contains no data");
    if (frames.values().stream().anyMatch(Map::isEmpty))
      throw new IOException("Every frame needs at least one transmitted reading");
    return frames.values().stream().map(Map::copyOf).toList();
  }
}
