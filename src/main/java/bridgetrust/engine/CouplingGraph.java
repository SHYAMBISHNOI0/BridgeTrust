package bridgetrust.engine;

import java.util.*;

/** Physical deck adjacency, not agreement between arbitrary distant sensors. */
public final class CouplingGraph {
  private final Map<String, List<String>> neighbours =
      Map.of(
          "S1",
          List.of("S2"),
          "S2",
          List.of("S1", "S3"),
          "S3",
          List.of("S2", "S4"),
          "S4",
          List.of("S3", "S5"),
          "S5",
          List.of("S4"));

  public List<String> neighbours(String id) {
    return neighbours.getOrDefault(id, List.of());
  }

  public double reconstruct(String id, Map<String, Double> valid) {
    return neighbours(id).stream()
        .filter(valid::containsKey)
        .mapToDouble(valid::get)
        .average()
        .orElse(Double.NaN);
  }

  public boolean coherent(String id, Map<String, Double> residuals, Set<String> excluded) {
    double own = residuals.getOrDefault(id, 0.0);
    return neighbours(id).stream()
        .filter(n -> !excluded.contains(n))
        .anyMatch(
            n -> {
              double other = residuals.getOrDefault(n, 0.0);
              return Math.abs(other) > 1.05 && own * other > 0 && Math.abs(own - other) < 1.4;
            });
  }
}
