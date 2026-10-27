package bridgetrust;

import static org.junit.jupiter.api.Assertions.*;

import bridgetrust.engine.*;
import bridgetrust.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ValidatorTest {
  private Map<String, Double> frame(int tick) {
    Map<String, Double> values = new HashMap<>();
    for (int i = 1; i <= 5; i++) values.put("S" + i, 24 + Math.sin(tick) * 0.1 + i * 0.01);
    values.put("T1", 15 + Math.cos(tick) * 0.1);
    return values;
  }

  @Test
  void isolatedFaultIsExcludedAndReconstructedWithoutInflatingSampleCount() {
    try (var validator = new Validator(Map.of(), true)) {
      validator.process(0, frame(0), 0);
      var first = frame(1);
      first.put("S3", 55.0);
      assertEquals(Verdict.QUESTIONABLE, validator.process(1, first, 0).sensor("S3").verdict());
      var second = frame(2);
      second.put("S3", 56.0);
      var result = validator.process(2, second, 0);
      assertEquals(Verdict.SENSOR_FAULT, result.sensor("S3").verdict());
      assertTrue(result.sensor("S3").virtualAvailable());
      assertEquals(4, result.estimate().contributors());
      assertEquals(24, result.estimate().value(), 0.3);
      assertTrue(result.sensor("S3").trust() < 1);
    }
  }

  @Test
  void adjacentCoherentShiftIsPreserved() {
    try (var validator = new Validator(Map.of(), false)) {
      Snapshot result = null;
      for (int i = 0; i < 5; i++) {
        var f = frame(i);
        f.compute("S2", (k, v) -> v + 6);
        f.compute("S3", (k, v) -> v + 6);
        result = validator.process(i, f, 0);
      }
      assertNotNull(result);
      assertEquals(Verdict.STRUCTURAL, result.sensor("S2").verdict());
      assertEquals(Verdict.STRUCTURAL, result.sensor("S3").verdict());
      assertEquals(5, result.estimate().contributors());
      assertTrue(result.estimate().value() > 26);
      assertEquals(1, result.sensor("S2").trust());
    }
  }

  @Test
  void distantAgreementDoesNotImplyPhysicalCoupling() {
    try (var validator = new Validator(Map.of(), false)) {
      Snapshot result = null;
      for (int i = 0; i < 3; i++) {
        var f = frame(i);
        f.compute("S1", (k, v) -> v + 6);
        f.compute("S5", (k, v) -> v + 6);
        result = validator.process(i, f, 0);
      }
      assertEquals(Verdict.SENSOR_FAULT, result.sensor("S1").verdict());
      assertEquals(Verdict.SENSOR_FAULT, result.sensor("S5").verdict());
    }
  }

  @Test
  void malformedMissingAndInvalidTemperatureFailSafely() {
    try (var validator = new Validator(Map.of(), false)) {
      var f = frame(0);
      f.put("S1", Double.NaN);
      f.remove("S2");
      var result = validator.process(0, f, 0);
      assertEquals(Verdict.SENSOR_FAULT, result.sensor("S1").verdict());
      assertEquals(Verdict.SENSOR_FAULT, result.sensor("S2").verdict());
      assertTrue(result.estimate().available());
      f = frame(1);
      f.put("T1", 200.0);
      result = validator.process(1, f, 0);
      assertEquals(Verdict.SENSOR_FAULT, result.sensor("T1").verdict());
      assertFalse(result.estimate().available());
      assertTrue(Double.isNaN(result.sensor("S1").compensated()));
    }
  }

  @Test
  void constantRawSignalBecomesStuckAndRecoversGradually() {
    try (var validator = new Validator(Map.of(), false)) {
      Snapshot result = null;
      for (int i = 0; i < 8; i++) {
        var f = frame(i);
        f.put("S2", 24.0);
        result = validator.process(i, f, 0);
      }
      assertEquals(Verdict.SENSOR_FAULT, result.sensor("S2").verdict());
      double low = result.sensor("S2").trust();
      result = validator.process(8, frame(8), 0);
      assertTrue(result.sensor("S2").trust() > low);
      assertEquals(Verdict.QUESTIONABLE, result.sensor("S2").verdict());
      for (int i = 9; i < 40; i++) result = validator.process(i, frame(i), 0);
      assertEquals(Verdict.HEALTHY, result.sensor("S2").verdict());
    }
  }

  @Test
  void thermalSwingDoesNotBecomeStructuralDamage() {
    try (var validator = new Validator(Map.of(), false)) {
      for (int i = 0; i < 80; i++) {
        double temp = 20 + 18 * Math.sin(i / 7.0);
        Map<String, Double> f = new HashMap<>();
        for (String id : Sensor.STRAIN) f.put(id, 24 + Sensor.K * (temp - Sensor.TREF));
        f.put("T1", temp);
        var result = validator.process(i, f, 0);
        assertEquals(24, result.estimate().value(), 1e-8);
        assertEquals(0, result.count(Verdict.STRUCTURAL));
      }
    }
  }

  @Test
  void allFailedSensorsProduceExplicitUnavailableEstimate() {
    try (var validator = new Validator(Map.of(), false)) {
      var result = validator.process(0, Map.of(), 0);
      assertFalse(result.estimate().available());
      assertEquals(0, result.estimate().contributors());
      assertEquals(6, result.count(Verdict.SENSOR_FAULT));
    }
  }

  @Test
  void fusionHandlesEmptyNonfiniteZeroWeightAndSingleSensor() {
    assertTrue(Double.isNaN(Fusion.median(List.of())));
    assertFalse(Fusion.weighted(List.of(2.0), List.of(0.0)).available());
    var result = Fusion.weighted(List.of(Double.NaN, 10.0, 12.0), List.of(1.0, 1.0, 1.0));
    assertEquals(11, result.value());
    assertEquals(2, result.contributors());
    assertTrue(result.halfWidth() > 0);
    assertTrue(Double.isNaN(Fusion.weighted(List.of(1.0), List.of(1.0)).halfWidth()));
  }

  @Test
  void benchmarkHasNoHiddenTruthLeakAndBeatsBaselinesForInjectedCases() {
    var results = Benchmark.run();
    assertEquals(8, results.size());
    var spike =
        results.stream().filter(r -> r.scenario().equals("Sensor spike")).findFirst().orElseThrow();
    assertTrue(spike.accuracy() > 95);
    assertTrue(spike.engineRmse() < spike.meanRmse());
    var damage =
        results.stream()
            .filter(r -> r.scenario().equals("Structural shift"))
            .findFirst()
            .orElseThrow();
    assertTrue(damage.accuracy() > 95);
    assertTrue(damage.engineRmse() < damage.medianRmse());
  }
}
