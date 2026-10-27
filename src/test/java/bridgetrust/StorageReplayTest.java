package bridgetrust;

import static org.junit.jupiter.api.Assertions.*;

import bridgetrust.engine.*;
import bridgetrust.model.*;
import bridgetrust.store.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageReplayTest {
  @TempDir Path directory;

  @Test
  void strictReplayRejectsDuplicateUnknownUnorderedAndOversizedInputs() throws Exception {
    Path csv = directory.resolve("replay.csv");
    Files.writeString(csv, "tick,sensor,value\n0,S1,24\n0,T1,15\n1,S1,\n1,T1,16\n");
    assertEquals(2, CsvReplay.read(csv).size());
    for (String bad : List.of("0,S1,2\n0,S1,3", "0,X1,2", "1,S1,2\n0,S2,2", "0,S1,nope", "0,S1,")) {
      Files.writeString(csv, "tick,sensor,value\n" + bad + "\n");
      assertThrows(java.io.IOException.class, () -> CsvReplay.read(csv));
    }
  }

  @Test
  void trustSurvivesRestartAndCorruptCheckpointIsSafelyRejected() throws Exception {
    try (var storage = new FileStorage(directory, "first");
        var validator = new Validator(Map.of(), false)) {
      var snapshot =
          validator.process(
              0,
              Map.of("T1", 15.0, "S1", 24.0, "S2", 24.1, "S3", Double.NaN, "S4", 24.2, "S5", 24.3),
              0);
      storage.save(snapshot, validator.trust());
      assertEquals(validator.trust(), storage.loadTrust());
      assertTrue(
          Files.readString(storage.sessionDirectory().resolve("readings.csv"))
              .contains("SENSOR_FAULT"));
    }
    try (var second = new FileStorage(directory, "second")) {
      assertTrue(second.loadTrust().get("S3") < 1);
    }
    Files.writeString(directory.resolve("trust-v2.ser"), "not serialized data");
    try (var third = new FileStorage(directory, "third")) {
      assertTrue(third.loadTrust().isEmpty());
    }
  }

  @Test
  void assemblerHandlesMissingDuplicateAndLateReadingsWithoutHanging() throws Exception {
    var storage = new MemoryStorage();
    try (var engine = new Engine(storage)) {
      engine.queue().put(new Reading("S1", 0, 24));
      engine.queue().put(new Reading("S1", 0, 25));
      engine.queue().put(new Reading("T1", 0, 15));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (engine.latest() == null && System.nanoTime() < deadline) Thread.sleep(20);
      assertNotNull(engine.latest());
      assertEquals(Verdict.SENSOR_FAULT, engine.latest().sensor("S2").verdict());
      assertEquals(1, engine.latest().rejectedMessages());
      engine.queue().put(new Reading("S2", 0, 24));
      for (String id : Sensor.IDS)
        engine.queue().put(new Reading(id, 1, id.equals("T1") ? 15 : 24 + id.charAt(1) * 0.01));
      deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (engine.latest().tick() < 1 && System.nanoTime() < deadline) Thread.sleep(20);
      assertEquals(1, engine.latest().tick());
      assertEquals(2, engine.latest().rejectedMessages());
      assertEquals("", engine.error());
    }
  }

  @Test
  void csvEscapingRetainsQuotesAndNewlines() {
    assertEquals("\"a,\"\"b\"\"\nc\"", FileStorage.csv("a,\"b\"\nc"));
  }

  private static final class MemoryStorage implements Storage {
    public void save(Snapshot snapshot, Map<String, Double> trust) {}

    public Map<String, Double> loadTrust() {
      return Map.of();
    }

    public String status() {
      return "memory";
    }

    public void close() {}
  }
}
