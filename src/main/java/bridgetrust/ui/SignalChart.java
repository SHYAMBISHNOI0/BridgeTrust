package bridgetrust.ui;

import bridgetrust.model.Snapshot;
import java.util.*;
import java.util.function.ToDoubleFunction;
import javafx.scene.canvas.*;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

/** Bounded history, genuine confidence band, and crosshair inspection. Gaps remain gaps. */
public final class SignalChart extends Region {
  private final Canvas canvas = new Canvas();
  private List<Snapshot> data = List.of();
  private boolean baselines = true;
  private double mouse = -1;

  public SignalChart() {
    getChildren().add(canvas);
    setMinHeight(115);
    setPrefHeight(125);
    setOnMouseMoved(
        e -> {
          mouse = e.getX();
          draw();
        });
    setOnMouseExited(
        e -> {
          mouse = -1;
          draw();
        });
    setAccessibleText(
        "Live reliable strain, approximate 95 percent interval, plain mean and median-discard"
            + " baselines in microstrain.");
  }

  public void setData(List<Snapshot> points) {
    data = List.copyOf(points);
    draw();
  }

  public void setBaselines(boolean visible) {
    baselines = visible;
    draw();
  }

  @Override
  protected void layoutChildren() {
    canvas.setWidth(getWidth());
    canvas.setHeight(getHeight());
    draw();
  }

  private double y(double value, double min, double max, double h) {
    return 15 + (max - value) / (max - min) * (h - 47);
  }

  private double x(int i, double w) {
    return 47 + i * (w - 65) / Math.max(1, data.size() - 1);
  }

  private void draw() {
    double w = canvas.getWidth(), h = canvas.getHeight();
    if (w < 1 || h < 1) return;
    var g = canvas.getGraphicsContext2D();
    g.clearRect(0, 0, w, h);
    g.setFont(Font.font("Monospaced", 10));
    if (data.isEmpty()) {
      g.setFill(Color.web("#819099"));
      g.fillText("Waiting for validated measurements…", 45, h / 2);
      return;
    }
    double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
    for (var s : data) {
      double ci = Double.isFinite(s.estimate().halfWidth()) ? s.estimate().halfWidth() : 0;
      double[] values =
          baselines
              ? new double[] {
                s.estimate().value() - ci,
                s.estimate().value() + ci,
                s.plainMean(),
                s.medianDiscard()
              }
              : new double[] {s.estimate().value() - ci, s.estimate().value() + ci};
      for (double value : values)
        if (Double.isFinite(value)) {
          min = Math.min(min, value);
          max = Math.max(max, value);
        }
    }
    if (!Double.isFinite(min)) {
      min = 0;
      max = 1;
    }
    double padding = Math.max(0.8, (max - min) * 0.16);
    min -= padding;
    max += padding;
    for (int i = 0; i < 5; i++) {
      double value = min + (max - min) * i / 4, yy = y(value, min, max, h);
      g.setStroke(Color.web("#2b353b"));
      g.setLineWidth(0.6);
      g.strokeLine(47, yy, w - 18, yy);
      g.setFill(Color.web("#7c8b93"));
      g.fillText(String.format(Locale.ROOT, "%.1f", value), 6, yy + 4);
    }
    g.save();
    g.beginPath();
    g.rect(46, 10, w - 61, h - 39);
    g.clip();
    g.setFill(Color.web("#e6b485", 0.12));
    for (int i = 1; i < data.size(); i++) {
      var a = data.get(i - 1).estimate();
      var b = data.get(i).estimate();
      if (!Double.isFinite(a.halfWidth()) || !Double.isFinite(b.halfWidth())) continue;
      g.fillPolygon(
          new double[] {x(i - 1, w), x(i, w), x(i, w), x(i - 1, w)},
          new double[] {
            y(a.value() - a.halfWidth(), min, max, h),
            y(b.value() - b.halfWidth(), min, max, h),
            y(b.value() + b.halfWidth(), min, max, h),
            y(a.value() + a.halfWidth(), min, max, h)
          },
          4);
    }
    if (baselines) {
      line(g, Snapshot::plainMean, Color.web("#778893"), min, max, w, h, true);
      line(g, Snapshot::medianDiscard, Color.web("#79aa9d"), min, max, w, h, true);
    }
    line(g, s -> s.estimate().value(), Color.web("#e9b789"), min, max, w, h, false);
    g.restore();
    g.setFill(Color.web("#6e818b"));
    for (int i = 0; i < 5; i++) {
      int index = (int) ((data.size() - 1) * i / 4.0);
      g.fillText(
          "t+" + String.format(Locale.ROOT, "%.0fs", data.get(index).tick() * 0.5),
          x(index, w) - 12,
          h - 8);
    }
    if (mouse >= 47 && mouse <= w - 18) {
      int index = (int) Math.round((mouse - 47) / (w - 65) * (data.size() - 1));
      index = Math.clamp(index, 0, data.size() - 1);
      var s = data.get(index);
      double xx = x(index, w);
      g.setStroke(Color.web("#9babae", 0.5));
      g.setLineDashes(3, 4);
      g.strokeLine(xx, 15, xx, h - 32);
      g.setLineDashes();
      String tip =
          String.format(
              Locale.ROOT,
              "FRAME %d  ·  %s µε",
              s.tick(),
              Double.isFinite(s.estimate().value())
                  ? String.format(Locale.ROOT, "%.2f", s.estimate().value())
                  : "unavailable");
      double tx = Math.clamp(xx - 80, 48, Math.max(48, w - 255));
      g.setFill(Color.web("#2b383f"));
      g.fillRoundRect(tx, 9, 225, 28, 6, 6);
      g.setFill(Color.web("#eee5db"));
      g.fillText(tip, tx + 10, 27);
    }
  }

  private void line(
      GraphicsContext g,
      ToDoubleFunction<Snapshot> fn,
      Color color,
      double min,
      double max,
      double w,
      double h,
      boolean dashed) {
    g.setStroke(color);
    g.setLineWidth(dashed ? 1 : 2);
    if (dashed) g.setLineDashes(4, 5);
    else g.setLineDashes();
    for (int i = 1; i < data.size(); i++) {
      double a = fn.applyAsDouble(data.get(i - 1)), b = fn.applyAsDouble(data.get(i));
      if (Double.isFinite(a) && Double.isFinite(b))
        g.strokeLine(x(i - 1, w), y(a, min, max, h), x(i, w), y(b, min, max, h));
    }
    g.setLineDashes();
  }
}
