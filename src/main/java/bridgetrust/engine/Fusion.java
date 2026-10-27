package bridgetrust.engine;

import bridgetrust.model.Snapshot.Estimate;
import java.util.List;

public final class Fusion {
  private Fusion() {}

  public static double median(List<Double> values) {
    var sorted = values.stream().filter(Double::isFinite).sorted().toList();
    int n = sorted.size();
    return n == 0
        ? Double.NaN
        : n % 2 == 0 ? (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2 : sorted.get(n / 2);
  }

  public static double mad(List<Double> values) {
    double median = median(values);
    return median(values.stream().map(v -> Math.abs(v - median)).toList());
  }

  public static double medianDiscard(List<Double> values) {
    double med = median(values), threshold = Math.max(1.05, 3 * 1.4826 * mad(values));
    return values.stream()
        .filter(Double::isFinite)
        .filter(x -> Math.abs(x - med) <= threshold)
        .mapToDouble(Double::doubleValue)
        .average()
        .orElse(Double.NaN);
  }

  /** Approximate 95% interval; Kish effective sample size and unbiased weighted variance. */
  public static Estimate weighted(List<Double> values, List<Double> weights) {
    if (values.size() != weights.size())
      throw new IllegalArgumentException("Values and weights differ");
    double total = 0, squares = 0, sum = 0;
    int count = 0;
    for (int i = 0; i < values.size(); i++) {
      double x = values.get(i), w = weights.get(i);
      if (!Double.isFinite(x) || !Double.isFinite(w) || w <= 0) continue;
      total += w;
      squares += w * w;
      sum += w * x;
      count++;
    }
    if (count == 0) return new Estimate(Double.NaN, Double.NaN, 0, 0);
    double mean = sum / total, variance = 0, effective = total * total / squares;
    for (int i = 0; i < values.size(); i++) {
      double x = values.get(i), w = weights.get(i);
      if (Double.isFinite(x) && Double.isFinite(w) && w > 0) variance += w * Math.pow(x - mean, 2);
    }
    double denominator = total - squares / total;
    double interval =
        count < 2
            ? Double.NaN
            : 1.96 * Math.sqrt(Math.max(0.0256, variance / denominator) / effective);
    return new Estimate(mean, interval, effective, count);
  }
}
