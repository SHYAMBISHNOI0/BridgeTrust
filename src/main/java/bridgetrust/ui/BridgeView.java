package bridgetrust.ui;

import bridgetrust.model.*;
import java.util.*;
import java.util.function.Consumer;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.canvas.*;
import javafx.scene.layout.Region;
import javafx.scene.paint.*;
import javafx.scene.text.Font;

/** Procedural 3D geometry projected into JavaFX Canvas; works without a discrete GPU. */
public final class BridgeView extends Region {
  private record Vertex(double x, double y, double z) {}

  private record Face(List<Vertex> vertices, Color fill, Color stroke, double lineWidth) {}

  private final Canvas canvas = new Canvas();
  private final List<Face> geometry = new ArrayList<>();
  private final Map<String, Point2D> targets = new HashMap<>();
  private double yaw = -0.43, pitch = 0.39, zoom = 1, dragX, dragY, movement;
  private double phase;
  private String selected = "S3";
  private Snapshot snapshot;
  private boolean coupling = true;
  private Consumer<String> onSelect = id -> {};

  public BridgeView() {
    getChildren().add(canvas);
    setMinSize(350, 190);
    setPrefHeight(235);
    setAccessibleText(
        "Interactive bridge model. Drag to orbit, scroll to zoom. Select sensors using the sensor"
            + " buttons.");
    setCursor(Cursor.OPEN_HAND);
    setOnMousePressed(
        e -> {
          dragX = e.getX();
          dragY = e.getY();
          movement = 0;
          setCursor(Cursor.CLOSED_HAND);
        });
    setOnMouseDragged(
        e -> {
          double dx = e.getX() - dragX, dy = e.getY() - dragY;
          movement += Math.abs(dx) + Math.abs(dy);
          yaw += dx * 0.006;
          pitch = Math.clamp(pitch + dy * 0.004, 0.08, 1.35);
          dragX = e.getX();
          dragY = e.getY();
          draw();
        });
    setOnMouseReleased(
        e -> {
          setCursor(Cursor.OPEN_HAND);
          if (movement < 6)
            targets.entrySet().stream()
                .filter(p -> p.getValue().distance(e.getX(), e.getY()) < 30)
                .findFirst()
                .ifPresent(p -> onSelect.accept(p.getKey()));
        });
    setOnScroll(
        e -> {
          zoom = Math.clamp(zoom * (e.getDeltaY() > 0 ? 1.08 : 0.92), 0.65, 1.8);
          draw();
          e.consume();
        });
  }

  public void onSelect(Consumer<String> callback) {
    onSelect = callback;
  }

  public void select(String id) {
    selected = id;
    draw();
  }

  public void update(Snapshot value, double time) {
    snapshot = value;
    phase = time;
    draw();
  }

  public void setCoupling(boolean value) {
    coupling = value;
    draw();
  }

  public void reset() {
    yaw = -0.43;
    pitch = 0.39;
    zoom = 1;
    draw();
  }

  public void plan() {
    yaw = 0;
    pitch = 1.30;
    zoom = 1;
    draw();
  }

  public void zoom(double multiplier) {
    zoom = Math.clamp(zoom * multiplier, 0.65, 1.8);
    draw();
  }

  @Override
  protected void layoutChildren() {
    canvas.setWidth(getWidth());
    canvas.setHeight(getHeight());
    draw();
  }

  private Point2D project(Vertex v) {
    double x = v.x * Math.cos(yaw) + v.z * Math.sin(yaw);
    double z = v.z * Math.cos(yaw) - v.x * Math.sin(yaw);
    double y = v.y * Math.cos(pitch) - z * Math.sin(pitch);
    double scale = Math.min(canvas.getWidth() / 870, canvas.getHeight() / 360) * zoom;
    return new Point2D(canvas.getWidth() / 2 + x * scale, canvas.getHeight() * 0.64 - y * scale);
  }

  private double depth(Vertex v) {
    return (v.z * Math.cos(yaw) - v.x * Math.sin(yaw)) * Math.cos(pitch) + v.y * Math.sin(pitch);
  }

  private Vertex v(double x, double y, double z) {
    return new Vertex(x, y, z);
  }

  private void face(Color fill, Color stroke, Vertex... vertices) {
    geometry.add(new Face(List.of(vertices), fill, stroke, 0.65));
  }

  private void segment(Vertex a, Vertex b, Color color, double width) {
    geometry.add(new Face(List.of(a, b), null, color, width));
  }

  private void box(double x, double y, double z, double w, double h, double d, Color color) {
    Vertex a = v(x - w / 2, y, z - d / 2),
        b = v(x + w / 2, y, z - d / 2),
        c = v(x + w / 2, y, z + d / 2),
        e = v(x - w / 2, y, z + d / 2);
    Vertex f = v(x - w / 2, y + h, z - d / 2),
        g = v(x + w / 2, y + h, z - d / 2),
        i = v(x + w / 2, y + h, z + d / 2),
        j = v(x - w / 2, y + h, z + d / 2);
    Color edge = color.brighter().deriveColor(0, 0.65, 0.85, 0.8);
    face(color.deriveColor(0, 1, 0.65, 1), edge, a, b, g, f);
    face(color.deriveColor(0, 1, 0.85, 1), edge, b, c, i, g);
    face(color, edge, c, e, j, i);
    face(color.deriveColor(0, 1, 0.65, 1), edge, e, a, f, j);
    face(color.brighter(), edge, f, g, i, j);
  }

  private void build() {
    geometry.clear();
    Color grid = Color.web("#28333a"),
        steel = Color.web("#576167"),
        tower = Color.web("#8b8d83"),
        cable = Color.web("#c29d75");
    for (int x = -480; x <= 480; x += 40) segment(v(x, -66, -220), v(x, -66, 220), grid, 0.55);
    for (int z = -220; z <= 220; z += 40) segment(v(-480, -66, z), v(480, -66, z), grid, 0.55);
    // Abutments, deck, two pylon portals and cross bracing.
    box(-355, -65, 0, 58, 56, 127, Color.web("#343e43"));
    box(355, -65, 0, 58, 56, 127, Color.web("#343e43"));
    box(0, -10, 0, 750, 10, 93, steel);
    box(0, 0, 0, 750, 1, 81, Color.web("#454f54"));
    for (int x : new int[] {-184, 184}) {
      for (int z : new int[] {-55, 55}) {
        box(x, -66, z, 17, 245, 15, tower);
        box(x, -66, z, 34, 10, 32, steel);
      }
      box(x, 148, 0, 17, 9, 122, tower);
      box(x, 40, 0, 13, 8, 122, steel);
      segment(v(x, 48, -49), v(x, 142, 49), Color.web("#77827f"), 2);
      segment(v(x, 48, 49), v(x, 142, -49), Color.web("#77827f"), 2);
    }
    for (int z : new int[] {-46, 46}) {
      segment(v(-375, 9, z), v(375, 9, z), Color.web("#b1b2a2"), 1.3);
      for (int x = -360; x <= 360; x += 24)
        segment(v(x, 0, z), v(x, 9, z), Color.web("#818b89"), 0.7);
      for (int x = -360; x < 360; x += 6)
        segment(v(x, cableHeight(x), z * 1.18), v(x + 6, cableHeight(x + 6), z * 1.18), cable, 2.2);
      for (int x = -348; x <= 348; x += 24)
        segment(v(x, 7, z), v(x, cableHeight(x), z * 1.18), Color.web("#92948b"), 0.9);
    }
    for (int x = -365; x < 370; x += 28)
      segment(v(x, 2, 0), v(x + 13, 2, 0), Color.web("#bbb9a1"), 1.1);
    for (int i = 0; i < 8; i++) {
      double x = ((phase * (i % 2 == 0 ? 24 : -19) + i * 97 + 10000) % 720) - 360;
      box(
          x,
          2,
          i % 2 == 0 ? -18 : 18,
          13,
          5,
          7,
          i % 3 == 0 ? Color.web("#d6a777") : Color.web("#aab8b9"));
    }
    if (snapshot != null)
      for (int i = 0; i < 5; i++) {
        var sensor = snapshot.sensor("S" + (i + 1));
        if (sensor.verdict() == Verdict.STRUCTURAL)
          box(-300 + i * 150, 1, 0, 120, 1, 80, Color.web("#c38451", 0.24));
      }
    if (coupling)
      for (int i = 0; i < 4; i++)
        segment(
            v(-300 + i * 150, 14, 60), v(-150 + i * 150, 14, 60), Color.web("#6caf9b", 0.5), 1.2);
  }

  private double cableHeight(double x) {
    return Math.abs(x) <= 184
        ? 53 + 123 * Math.pow(x / 184, 2)
        : 176 * Math.pow((375 - Math.abs(x)) / 191, 0.7);
  }

  private void draw() {
    double width = canvas.getWidth(), height = canvas.getHeight();
    if (width < 1 || height < 1) return;
    GraphicsContext g = canvas.getGraphicsContext2D();
    g.setFill(Color.web("#151d22"));
    g.fillRect(0, 0, width, height);
    g.setFill(
        new RadialGradient(
            0,
            0,
            0.52,
            0.50,
            0.65,
            true,
            CycleMethod.NO_CYCLE,
            new Stop(0, Color.web("#253238", 0.75)),
            new Stop(1, Color.TRANSPARENT)));
    g.fillRect(0, 0, width, height);
    build();
    geometry.sort(
        Comparator.comparingDouble(
            f -> f.vertices.stream().mapToDouble(this::depth).average().orElse(0)));
    for (Face face : geometry) {
      double[] xs = new double[face.vertices.size()], ys = new double[xs.length];
      for (int i = 0; i < xs.length; i++) {
        var p = project(face.vertices.get(i));
        xs[i] = p.getX();
        ys[i] = p.getY();
      }
      g.setLineWidth(face.lineWidth);
      g.setStroke(face.stroke);
      if (xs.length == 2) g.strokeLine(xs[0], ys[0], xs[1], ys[1]);
      else {
        g.setFill(face.fill);
        g.fillPolygon(xs, ys, xs.length);
        g.strokePolygon(xs, ys, xs.length);
      }
    }
    targets.clear();
    g.setFont(Font.font("Monospaced", 11));
    for (int i = 0; i < 5; i++) {
      String id = "S" + (i + 1);
      Point2D anchor = project(v(-300 + i * 150, 12, 60));
      Point2D point = anchor.add(0, 30 + (i % 2) * 10);
      targets.put(id, point);
      Color color =
          snapshot == null ? Color.web("#7fceb1") : Color.web(snapshot.sensor(id).verdict().color);
      g.setStroke(color.deriveColor(0, 1, 1, 0.55));
      g.setLineWidth(1);
      g.strokeLine(anchor.getX(), anchor.getY(), point.getX(), point.getY());
      g.setFill(color.deriveColor(0, 1, 1, 0.10));
      g.fillOval(anchor.getX() - 11, anchor.getY() - 11, 22, 22);
      g.setFill(color);
      g.fillOval(anchor.getX() - 3.5, anchor.getY() - 3.5, 7, 7);
      g.setFill(id.equals(selected) ? Color.web("#dcb58c") : Color.web("#202a30"));
      g.fillRoundRect(point.getX() - 19, point.getY() - 10, 38, 22, 7, 7);
      g.setStroke(id.equals(selected) ? Color.web("#efc39b") : Color.web("#43534f"));
      g.strokeRoundRect(point.getX() - 19, point.getY() - 10, 38, 22, 7, 7);
      g.setFill(id.equals(selected) ? Color.web("#1c2528") : color);
      g.fillText(id, point.getX() - 7, point.getY() + 5);
    }
    g.setFill(Color.web("#72838b"));
    g.setFont(Font.font("Monospaced", 10));
    g.fillText("WEST ABUTMENT", 20, height - 22);
    g.fillText("EAST ABUTMENT", width - 115, height - 22);
    g.setStroke(Color.web("#74858a"));
    g.strokeLine(width / 2 - 36, height - 25, width / 2 + 36, height - 25);
    g.strokeLine(width / 2 - 36, height - 28, width / 2 - 36, height - 22);
    g.strokeLine(width / 2 + 36, height - 28, width / 2 + 36, height - 22);
    g.fillText("SCHEMATIC · 3D", width / 2 - 44, height - 10);
    g.setFill(Color.web("#829198"));
    g.fillText("N", width - 32, 30);
    g.setStroke(Color.web("#bdc9c8"));
    g.strokeLine(width - 29, 56, width - 29, 35);
    g.strokeLine(width - 29, 35, width - 33, 42);
    g.strokeLine(width - 29, 35, width - 25, 42);
  }
}
