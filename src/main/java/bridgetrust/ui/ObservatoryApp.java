package bridgetrust.ui;

import bridgetrust.engine.*;
import bridgetrust.model.*;
import bridgetrust.model.Snapshot.*;
import bridgetrust.store.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import javafx.animation.*;
import javafx.application.*;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.*;
import javafx.scene.shape.SVGPath;
import javafx.stage.*;
import javafx.util.Duration;
import javax.imageio.ImageIO;

/** Native JavaFX desktop workspace. All disk work and evaluation run off the application thread. */
public final class ObservatoryApp extends Application {
  private final BorderPane root = new BorderPane();
  private final Map<String, Button> navigation = new LinkedHashMap<>();
  private final Map<String, Node> pages = new HashMap<>();
  private final Map<String, Button> sensorButtons = new LinkedHashMap<>();
  private final ExecutorService tasks =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "desktop-io");
            t.setDaemon(true);
            return t;
          });
  private final BridgeView bridge = new BridgeView();
  private final SignalChart chart = new SignalChart(), labChart = new SignalChart();
  private final VBox inspectorChecks = new VBox(9),
      eventPreview = new VBox(9),
      benchmarkResults = new VBox(12);
  private final Label estimate = label("—", "metric-number"),
      confidence = label("Waiting for measurements", "muted"),
      trust = label("—", "metric-number"),
      coverage = label("—", "metric-number"),
      alerts = label("0", "metric-number");
  private final Label status = label("Starting validation nodes…", "status-text"),
      source = label("SIMULATION", "source-badge"),
      clockLabel = label("", "mono"),
      notice = label("", "notice");
  private final Label selectedId = label("S3", "inspector-id"),
      selectedState = label("Healthy", "badge"),
      selectedValue = label("—", "inspector-value"),
      selectedUnit = label("µε", "muted"),
      selectedTrust = label("—", "mono"),
      explanation = label("Waiting for sensor evidence.", "muted"),
      virtual = label("", "virtual-note"),
      tempLabel = label("— °C", "mono"),
      latency = label("— ms", "mono");
  private final ProgressBar selectedProgress = new ProgressBar(1);
  private final Label eventSummary = label("No active anomalies", "muted");
  private final TableView<SensorState> sensorTable = new TableView<>();
  private final TableView<Event> auditTable = new TableView<>();
  private final ComboBox<String> eventFilter = new ComboBox<>();
  private final Set<String> acknowledged = new HashSet<>();
  private final Label auditDetail =
      label("Select an event to inspect its recorded evidence.", "body-copy");
  private final Label scenarioDescription =
      label("Healthy traffic and a daily thermal cycle.", "muted");
  private final Map<Simulator.Scenario, Button> scenarioButtons =
      new EnumMap<>(Simulator.Scenario.class);
  private Button pause;
  private Stage stage;
  private Engine engine;
  private Simulator simulator;
  private volatile AuditStore store;
  private Timeline refresh;
  private String selected = "S3";
  private int lastTick = -1;
  private String currentPage = "Overview";
  private List<Benchmark.Result> benchmark = List.of();
  private volatile boolean stopped;
  private final long started = System.nanoTime();

  @Override
  public void start(Stage stage) {
    this.stage = stage;
    root.getStyleClass().add("workspace");
    root.setLeft(sidebar());
    root.setTop(topbar());
    Node systemBar = footer();
    systemBar.getStyleClass().add("system-bar");
    root.setBottom(systemBar);
    root.setCenter(new StackPane(label("Opening the observatory…", "page-title")));
    Scene scene = new Scene(root, 1450, 970);
    scene
        .getStylesheets()
        .add(Objects.requireNonNull(getClass().getResource("observatory.css")).toExternalForm());
    stage.setTitle("BridgeTrust — Structural Observatory");
    stage
        .getIcons()
        .add(
            new javafx.scene.image.Image(
                Objects.requireNonNull(getClass().getResourceAsStream("app-icon.png"))));
    stage.setMinWidth(1180);
    stage.setMinHeight(820);
    stage.setScene(scene);
    stage.show();
    scene.setOnKeyPressed(
        e -> {
          if (e.getTarget() instanceof TextInputControl || e.getTarget() instanceof ComboBoxBase<?>)
            return;
          switch (e.getCode()) {
            case SPACE -> {
              if (simulator != null) togglePause();
              e.consume();
            }
            case DIGIT1 -> show("Overview");
            case DIGIT2 -> show("Sensor network");
            case DIGIT3 -> show("Scenario lab");
            case DIGIT4 -> show("Event journal");
            case R -> bridge.reset();
            default -> {}
          }
        });
    CompletableFuture.supplyAsync(
            () -> {
              try {
                return new AuditStore(
                    Path.of(
                        System.getenv()
                            .getOrDefault(
                                "BT_DATA_DIR",
                                Path.of(System.getProperty("user.home"), ".bridgetrust")
                                    .toString())));
              } catch (Exception ex) {
                throw new CompletionException(ex);
              }
            },
            tasks)
        .whenComplete(
            (storage, error) -> {
              // Publish the resource before scheduling UI work so closing during JDBC startup
              // cannot leave an audit thread alive after the JavaFX toolkit exits.
              store = storage;
              if (stopped) {
                if (storage != null) storage.close();
                return;
              }
              Platform.runLater(
                  () -> {
                    if (error != null) {
                      root.setCenter(
                          new StackPane(
                              label(
                                  "Unable to open local audit storage. Check BT_DATA_DIR"
                                      + " permissions.",
                                  "body-copy")));
                      return;
                    }
                    if (stopped) {
                      storage.close();
                      return;
                    }
                    store = storage;
                    engine = new Engine(store);
                    simulator = new Simulator(engine.queue());
                    pages.put("Overview", overview());
                    pages.put("Sensor network", network());
                    pages.put("Scenario lab", laboratory());
                    pages.put("Event journal", journal());
                    show("Overview");
                    refresh = new Timeline(new KeyFrame(Duration.millis(100), e -> update()));
                    refresh.setCycleCount(Animation.INDEFINITE);
                    refresh.play();
                    if (getParameters().getRaw().contains("--smoke")) smoke();
                  });
            });
  }

  private Node sidebar() {
    VBox side = new VBox(9);
    side.getStyleClass().add("sidebar");
    side.setPrefWidth(196);
    HBox brand =
        new HBox(
            10,
            icon("M1 20 L7 3 L10 3 L16 20 M3 14 L14 14 M21 20 L27 3 L30 3 L36 20 M23 14 L34 14"),
            label("BridgeTrust", "brand"));
    brand.setAlignment(Pos.CENTER_LEFT);
    side.getChildren()
        .addAll(
            brand,
            label("STRUCTURAL OBSERVATORY", "brand-caption"),
            spacer(31),
            label("WORKSPACE", "eyebrow"));
    String[] icons = {
      "M2 2 H8 V8 H2 Z M12 2 H18 V8 H12 Z M2 12 H8 V18 H2 Z M12 12 H18 V18 H12 Z",
      "M3 10 L10 3 L17 10 L10 17 Z M3 10 H17 M10 3 V17",
      "M5 2 H15 M8 2 V8 L2 18 H18 L12 8 V2 M5 13 H15",
      "M3 2 H17 V18 H3 Z M6 6 H14 M6 10 H14 M6 14 H11"
    };
    int i = 0;
    for (String name : List.of("Overview", "Sensor network", "Scenario lab", "Event journal")) {
      Button button = new Button(name, icon(icons[i++]));
      button.getStyleClass().add("nav-button");
      button.setMaxWidth(Double.MAX_VALUE);
      button.setOnAction(e -> show(name));
      navigation.put(name, button);
      side.getChildren().add(button);
    }
    Region spring = new Region();
    VBox.setVgrow(spring, Priority.ALWAYS);
    side.getChildren()
        .addAll(
            spring,
            label("CONNECTED ASSET", "eyebrow"),
            label("Northspan", "asset-name"),
            label("BT–001 / Virtual bridge", "muted"),
            spacer(12),
            label("●  Simulation environment", "green-text"),
            label("Java 21 · Desktop edition", "small-muted"),
            spacer(13),
            label("CSE1007 / v2.0", "mono"));
    return side;
  }

  private Node topbar() {
    HBox bar = new HBox(14);
    bar.getStyleClass().add("topbar");
    bar.setAlignment(Pos.CENTER_LEFT);
    Label breadcrumb = label("WORKSPACE  /  NORTHSPAN", "eyebrow");
    HBox.setHgrow(breadcrumb, Priority.ALWAYS);
    breadcrumb.setMaxWidth(Double.MAX_VALUE);
    pause = button("Ⅱ  Pause stream", "secondary-button", this::togglePause);
    pause.setTooltip(new Tooltip("Pause acquisition · Space"));
    bar.getChildren()
        .addAll(
            breadcrumb,
            source,
            clockLabel,
            pause,
            button("Export readings  ↗", "primary-button", this::exportReadings));
    return bar;
  }

  private Node overview() {
    VBox page =
        page(
            "The bridge, in perspective.",
            "Observe the structure. Understand the signal.",
            "01 / LIVE OVERVIEW");
    HBox metrics =
        new HBox(
            12,
            metric("RELIABLE STRAIN", estimate, confidence, "µε"),
            metric("NETWORK TRUST", trust, label("Behaviour-weighted confidence", "muted"), "%"),
            metric(
                "LIVE CHANNELS", coverage, label("5 strain + 1 thermal reference", "muted"), "/ 6"),
            metric("ACTIVE ANOMALIES", alerts, label("Faults + structural alerts", "muted"), ""));
    for (Node node : metrics.getChildren()) HBox.setHgrow(node, Priority.ALWAYS);
    VBox left = new VBox(12);
    HBox.setHgrow(left, Priority.ALWAYS);
    left.setMinWidth(450);
    VBox model = new VBox();
    model.getStyleClass().add("model-card");
    HBox modelHeader =
        row(
            label("Northspan digital twin", "section-title"),
            stretch(),
            label("● LIVE TOPOLOGY", "green-text"));
    modelHeader.getStyleClass().add("card-header");
    HBox tools =
        row(
            button("↻  Reset view", "quiet-button", bridge::reset),
            button("Top view", "quiet-button", bridge::plan),
            stretch());
    CheckBox graph = new CheckBox("Coupling graph");
    graph.setSelected(true);
    graph.setOnAction(e -> bridge.setCoupling(graph.isSelected()));
    tools
        .getChildren()
        .addAll(
            graph,
            button("−", "square-button", () -> bridge.zoom(0.9)),
            button("+", "square-button", () -> bridge.zoom(1.1)));
    tools.getStyleClass().add("model-tools");
    bridge.onSelect(this::selectSensor);
    VBox.setVgrow(bridge, Priority.ALWAYS);
    HBox chips = new HBox(7);
    chips.getStyleClass().add("sensor-strip");
    for (String id : Sensor.IDS) {
      Button b = button(id + "  ●", "sensor-chip", () -> selectSensor(id));
      b.setMaxWidth(Double.MAX_VALUE);
      HBox.setHgrow(b, Priority.ALWAYS);
      sensorButtons.put(id, b);
      chips.getChildren().add(b);
    }
    HBox hint =
        row(
            label("Drag to orbit  ·  Scroll to zoom  ·  Select a sensor", "small-muted"),
            stretch());
    hint.getStyleClass().add("model-hint");
    model.getChildren().addAll(modelHeader, tools, bridge, chips, hint);
    VBox signal = card();
    CheckBox baseline = new CheckBox("Show baselines");
    baseline.setSelected(true);
    baseline.setOnAction(e -> chart.setBaselines(baseline.isSelected()));
    signal
        .getChildren()
        .addAll(
            row(label("Signal intelligence", "section-title"), stretch(), baseline),
            row(
                label("— Reliable estimate", "amber-text"),
                label("▪ 95% interval", "muted"),
                label("-- Plain mean", "muted"),
                label("-- Median discard", "green-text")),
            chart);
    left.getChildren().addAll(model, signal);
    VBox right = new VBox(16, inspector(), recentEvents());
    right.setPrefWidth(290);
    right.setMinWidth(290);
    right.setMaxWidth(290);
    HBox body = new HBox(16, left, right);
    page.getChildren().addAll(metrics, body);
    return scroll(page);
  }

  private Node inspector() {
    VBox panel = card();
    panel.getStyleClass().add("inspector");
    selectedProgress.setMaxWidth(Double.MAX_VALUE);
    selectedProgress.setPrefHeight(5);
    explanation.setWrapText(true);
    virtual.setWrapText(true);
    panel
        .getChildren()
        .addAll(
            label("SENSOR INSPECTOR", "eyebrow"),
            row(selectedId, stretch(), selectedState),
            row(selectedValue, selectedUnit),
            row(label("TRUST SCORE", "eyebrow"), stretch(), selectedTrust),
            selectedProgress,
            new Separator(),
            label("VALIDATION EVIDENCE", "eyebrow"),
            inspectorChecks,
            new Separator(),
            explanation,
            virtual);
    return panel;
  }

  private Node recentEvents() {
    VBox box = card();
    box.getChildren()
        .addAll(
            row(
                label("Recent activity", "section-title"),
                stretch(),
                button("↗", "quiet-button", () -> show("Event journal"))),
            eventSummary,
            eventPreview);
    return box;
  }

  private Node network() {
    VBox page =
        page(
            "Every sensor has a story.",
            "Measured values, physical relationships and the evidence behind trust.",
            "02 / SENSOR NETWORK");
    VBox topology = card();
    HBox chain = new HBox(12);
    chain.setAlignment(Pos.CENTER);
    for (int i = 0; i < 5; i++) {
      String id = "S" + (i + 1);
      Button b =
          button(
              id + "\nSTRAIN",
              "topology-node",
              () -> {
                selectSensor(id);
                show("Overview");
              });
      chain.getChildren().add(b);
      if (i < 4) chain.getChildren().add(label("⟷", "mono"));
    }
    topology
        .getChildren()
        .addAll(
            row(
                label("Physical coupling", "section-title"),
                stretch(),
                label("T1 · shared thermal reference", "muted")),
            chain,
            label(
                "Only direct neighbours may reconstruct a failed sensor. Synthetic values never"
                    + " count as independent evidence.",
                "body-copy"));
    sensorTable.getColumns().add(column("CHANNEL", s -> s.id(), 95));
    sensorTable.getColumns().add(column("RAW", s -> fmt(s.raw()), 115));
    sensorTable.getColumns().add(column("COMPENSATED", s -> fmt(s.compensated()), 145));
    sensorTable
        .getColumns()
        .add(column("TRUST", s -> String.format(Locale.ROOT, "%.0f%%", s.trust() * 100), 100));
    sensorTable.getColumns().add(column("VERDICT", s -> s.verdict().label, 155));
    sensorTable.getColumns().add(column("VIRTUAL VALUE", s -> fmt(s.reconstructed()), 150));
    sensorTable.getColumns().add(column("EVIDENCE", SensorState::explanation, 400));
    sensorTable.setPrefHeight(330);
    sensorTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    sensorTable.setOnMouseClicked(
        e -> {
          if (e.getClickCount() == 2 && sensorTable.getSelectionModel().getSelectedItem() != null) {
            selectSensor(sensorTable.getSelectionModel().getSelectedItem().id());
            show("Overview");
          }
        });
    VBox table = card();
    table
        .getChildren()
        .addAll(
            row(
                label("Live sensor registry", "section-title"),
                stretch(),
                label("Double-click to inspect", "muted")),
            sensorTable);
    VBox method = card();
    method
        .getChildren()
        .addAll(
            label("How a verdict is formed", "section-title"),
            label(
                "01  Range + stuck checks identify hard failures.\n"
                    + "02  Neighbour deviation + two-sided CUSUM identify suspicious behaviour.\n"
                    + "03  Adjacent sensors shifting together for two frames produce a structural"
                    + " alert.\n"
                    + "04  Verified temperature compensation precedes trust-weighted fusion.",
                "body-copy"),
            label(
                "A shared change across most sensors can be indistinguishable from genuine load."
                    + " This model flags local coherence; it is not a certified damage diagnosis.",
                "muted"));
    page.getChildren().addAll(topology, table, method);
    return scroll(page);
  }

  private Node laboratory() {
    VBox page =
        page(
            "Put trust to the test.",
            "Inject a fault. Change the structure. See how the engine responds.",
            "03 / SCENARIO LAB");
    GridPane grid = new GridPane();
    grid.setHgap(12);
    grid.setVgap(12);
    int i = 0;
    for (var scenario : Simulator.Scenario.values()) {
      Button b =
          button(
              scenario.title + "\n" + scenario.description,
              "scenario-button",
              () -> applyScenario(scenario));
      b.setWrapText(true);
      b.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
      b.setMinHeight(95);
      b.setAlignment(Pos.CENTER_LEFT);
      scenarioButtons.put(scenario, b);
      grid.add(b, i % 4, i / 4);
      GridPane.setHgrow(b, Priority.ALWAYS);
      i++;
    }
    for (int c = 0; c < 4; c++) {
      ColumnConstraints constraints = new ColumnConstraints();
      constraints.setPercentWidth(25);
      grid.getColumnConstraints().add(constraints);
    }
    VBox comparison = card();
    comparison
        .getChildren()
        .addAll(
            row(
                label("Live comparison", "section-title"),
                stretch(),
                button("Import CSV replay", "secondary-button", this::importReplay)),
            scenarioDescription,
            row(
                label("— BridgeTrust", "amber-text"),
                label("-- Plain mean", "muted"),
                label("-- Median discard", "green-text")),
            labChart);
    Button run = button("Run reproducible evaluation  →", "primary-button", () -> runBenchmark());
    run.setId("run-benchmark");
    VBox evaluation = card();
    evaluation
        .getChildren()
        .addAll(
            row(label("Evidence, not a demo score.", "section-title"), stretch(), run),
            label(
                "8 scenarios · fixed seeds · 200 frames each · score frames 65–199 after warm-up."
                    + " Ground truth never enters validation.",
                "muted"),
            benchmarkResults);
    benchmarkResults
        .getChildren()
        .add(
            label(
                "Run evaluation to measure detection, classification, false alarms and estimation"
                    + " error against both baselines.",
                "body-copy"));
    page.getChildren().addAll(grid, comparison, evaluation);
    return scroll(page);
  }

  private Node journal() {
    VBox page =
        page(
            "An audit trail, not a black box.",
            "Every state transition carries its evidence. Acknowledge review without erasing"
                + " history.",
            "04 / EVENT JOURNAL");
    eventFilter.setItems(
        FXCollections.observableArrayList(
            "All verdicts", "Sensor fault", "Structural", "Questionable", "Healthy"));
    eventFilter.getSelectionModel().selectFirst();
    eventFilter.setOnAction(e -> refreshEvents());
    VBox panel = card();
    panel
        .getChildren()
        .add(
            row(
                label("State transitions", "section-title"),
                stretch(),
                eventFilter,
                button("Acknowledge selected", "secondary-button", this::acknowledge)));
    auditTable.getColumns().add(column("TIME", e -> time(e.time()), 110));
    auditTable.getColumns().add(column("FRAME", e -> Integer.toString(e.tick()), 75));
    auditTable.getColumns().add(column("SENSOR", Event::sensor, 90));
    auditTable.getColumns().add(column("VERDICT", e -> e.verdict().label, 150));
    auditTable
        .getColumns()
        .add(
            column(
                "REVIEW",
                e -> acknowledged.contains(eventKey(e)) ? "Acknowledged" : "Unreviewed",
                145));
    auditTable.getColumns().add(column("EVIDENCE", Event::detail, 450));
    auditTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    auditTable.setPrefHeight(400);
    auditTable.setPlaceholder(
        label("No state transitions yet. Try a scenario in the lab.", "muted"));
    auditTable
        .getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (o, a, b) -> {
              if (b != null)
                auditDetail.setText(b.sensor() + " · " + b.verdict().label + "\n" + b.detail());
            });
    auditDetail.setWrapText(true);
    panel
        .getChildren()
        .addAll(
            auditTable,
            auditDetail,
            label(
                "Review markers belong to this desktop session. Persistent measurement and event"
                    + " evidence is stored separately.",
                "small-muted"));
    VBox storage = card();
    storage
        .getChildren()
        .addAll(
            label("Storage & provenance", "section-title"),
            label(
                "All frames, five-layer evidence, estimates and state transitions are written to"
                    + " the session audit. If MySQL is configured, complete frames are mirrored in"
                    + " JDBC transactions.",
                "body-copy"),
            label("Session: " + store.sessionDirectory(), "mono"),
            button("Export event journal  ↗", "secondary-button", this::exportEvents));
    page.getChildren().addAll(panel, storage);
    return scroll(page);
  }

  private void update() {
    if (engine == null) return;
    Snapshot s = engine.latest();
    clockLabel.setText(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
    source.setText(simulator.source());
    pause.setText(simulator.paused() ? "▶  Resume stream" : "Ⅱ  Pause stream");
    status.setText(
        !engine.error().isEmpty()
            ? engine.error()
            : !simulator.error().isEmpty()
                ? simulator.error()
                : store.status()
                    + "  ·  "
                    + (simulator.paused() ? "Acquisition paused" : "2 validation nodes online"));
    if (s == null) return;
    bridge.update(s, simulator.paused() ? s.tick() * 0.5 : (System.nanoTime() - started) / 1e9);
    if (s.tick() == lastTick) return;
    lastTick = s.tick();
    estimate.setText(fmt(s.estimate().value()));
    confidence.setText(
        Double.isFinite(s.estimate().halfWidth())
            ? "± " + fmt(s.estimate().halfWidth()) + " · approx. 95% interval"
            : "Interval unavailable");
    trust.setText(
        String.format(
            Locale.ROOT,
            "%.0f",
            s.sensors().stream().mapToDouble(SensorState::trust).average().orElse(0) * 100));
    coverage.setText(
        Long.toString(s.sensors().stream().filter(r -> Double.isFinite(r.raw())).count()));
    alerts.setText(Long.toString(s.count(Verdict.SENSOR_FAULT) + s.count(Verdict.STRUCTURAL)));
    alerts.setStyle(
        "-fx-text-fill: "
            + (s.count(Verdict.STRUCTURAL) > 0
                ? "#f4a66a"
                : s.count(Verdict.SENSOR_FAULT) > 0 ? "#ef8c8c" : "#e8ece8")
            + ";");
    tempLabel.setText(fmt(s.temperature()) + " °C");
    latency.setText(fmt(s.processingMs()) + " ms");
    for (var r : s.sensors()) {
      var b = sensorButtons.get(r.id());
      if (b != null) {
        b.setText(r.id() + "  " + String.format(Locale.ROOT, "%.0f%%", r.trust() * 100));
        b.setStyle("-fx-text-fill: " + r.verdict().color + ";");
      }
    }
    var history = engine.history();
    int from = Math.max(0, history.size() - 180);
    chart.setData(history.subList(from, history.size()));
    labChart.setData(history.subList(from, history.size()));
    sensorTable.getItems().setAll(s.sensors());
    updateInspector(s);
    refreshEvents();
    eventSummary.setText(
        s.count(Verdict.STRUCTURAL) > 0
            ? "Structural shift · investigate evidence"
            : s.count(Verdict.SENSOR_FAULT) > 0
                ? "Sensor faults · estimation protected"
                : "No active anomalies");
    eventPreview.getChildren().clear();
    for (var event : s.events().stream().limit(3).toList()) {
      Label title = label(event.sensor() + "  /  " + event.verdict().label, "event-title");
      title.setStyle("-fx-text-fill: " + event.verdict().color + ";");
      eventPreview
          .getChildren()
          .add(
              new VBox(
                  3,
                  title,
                  label(time(event.time()) + "  ·  frame " + event.tick(), "small-muted")));
    }
    if (s.events().isEmpty())
      eventPreview
          .getChildren()
          .add(
              label(
                  "Listening for state changes.\nEvery transition will appear here.",
                  "small-muted"));
  }

  private void updateInspector(Snapshot snapshot) {
    var s = snapshot.sensor(selected);
    selectedId.setText(s.id());
    selectedState.setText(s.verdict().label);
    selectedState.setStyle("-fx-text-fill: " + s.verdict().color + ";");
    selectedValue.setText(fmt(s.compensated()));
    selectedUnit.setText(s.id().equals("T1") ? "°C" : "µε");
    selectedTrust.setText(String.format(Locale.ROOT, "%.0f%%", s.trust() * 100));
    selectedProgress.setProgress(s.trust());
    selectedProgress.setStyle("-fx-accent: " + s.verdict().color + ";");
    explanation.setText(s.explanation());
    virtual.setText(
        s.virtualAvailable()
            ? "VIRTUAL SENSOR  /  "
                + fmt(s.reconstructed())
                + " µε\nRebuilt from direct neighbours; excluded from independent sample count."
            : s.verdict() == Verdict.SENSOR_FAULT
                ? "No eligible neighbour reconstruction available."
                : "");
    virtual.setManaged(!virtual.getText().isBlank());
    virtual.setVisible(virtual.isManaged());
    inspectorChecks.getChildren().clear();
    for (var evidence : s.evidence()) {
      String symbol =
          switch (evidence.outcome()) {
            case PASS -> "✓";
            case WATCH -> "!";
            case FAIL -> "×";
            case UNAVAILABLE -> "·";
          };
      Label state = label(symbol, "check-symbol");
      state.setStyle(
          "-fx-text-fill: "
              + switch (evidence.outcome()) {
                case PASS -> "#7fceb1";
                case WATCH -> "#e6c277";
                case FAIL -> "#ef8c8c";
                case UNAVAILABLE -> "#72848c";
              }
              + ";");
      HBox row = row(label(evidence.stage(), "check-name"), stretch(), state);
      Tooltip.install(row, new Tooltip(evidence.detail()));
      inspectorChecks.getChildren().add(row);
    }
  }

  private void selectSensor(String id) {
    selected = id;
    bridge.select(id);
    sensorButtons.forEach(
        (key, b) -> {
          b.getStyleClass().remove("selected-sensor");
          if (key.equals(id)) b.getStyleClass().add("selected-sensor");
        });
    if (engine != null && engine.latest() != null) updateInspector(engine.latest());
  }

  private void show(String page) {
    if (!pages.containsKey(page)) return;
    currentPage = page;
    root.setCenter(pages.get(page));
    navigation.forEach(
        (name, b) -> {
          b.getStyleClass().remove("nav-active");
          if (name.equals(page)) b.getStyleClass().add("nav-active");
        });
  }

  private void togglePause() {
    if (simulator != null) simulator.setPaused(!simulator.paused());
  }

  private void applyScenario(Simulator.Scenario scenario) {
    simulator.setScenario(scenario);
    scenarioDescription.setText(
        scenario.description + " Trust recovers gradually when the fault clears.");
    scenarioButtons.forEach(
        (s, b) -> {
          b.getStyleClass().remove("scenario-active");
          if (s == scenario) b.getStyleClass().add("scenario-active");
        });
    toast("Scenario running: " + scenario.title);
  }

  private void refreshEvents() {
    if (engine == null || engine.latest() == null) return;
    Event selectedEvent = auditTable.getSelectionModel().getSelectedItem();
    String filter = eventFilter.getValue();
    var events =
        engine.latest().events().stream()
            .filter(
                e ->
                    filter == null
                        || filter.equals("All verdicts")
                        || filter.equals(e.verdict().label))
            .toList();
    if (!auditTable.getItems().equals(events)) {
      auditTable.getItems().setAll(events);
      if (selectedEvent != null) auditTable.getSelectionModel().select(selectedEvent);
    }
  }

  private String eventKey(Event e) {
    return e.tick() + ":" + e.sensor();
  }

  private void acknowledge() {
    var event = auditTable.getSelectionModel().getSelectedItem();
    if (event == null) {
      toast("Select an event first.");
      return;
    }
    acknowledged.add(eventKey(event));
    auditTable.refresh();
    toast("Review acknowledged for " + event.sensor() + ". Evidence retained.");
  }

  private void importReplay() {
    FileChooser chooser = new FileChooser();
    chooser.setTitle("Replay sensor readings");
    chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Sensor CSV", "*.csv"));
    var file = chooser.showOpenDialog(stage);
    if (file == null) return;
    CompletableFuture.supplyAsync(
            () -> {
              try {
                return CsvReplay.read(file.toPath());
              } catch (Exception ex) {
                throw new CompletionException(ex);
              }
            },
            tasks)
        .whenComplete(
            (frames, error) ->
                Platform.runLater(
                    () -> {
                      if (error != null) error("Replay could not be opened", error);
                      else {
                        simulator.replay(frames);
                        scenarioDescription.setText(
                            "Replaying "
                                + frames.size()
                                + " frames from "
                                + file.getName()
                                + " at 2 Hz. Original tick spacing is normalized.");
                        toast("CSV replay loaded. Scenario buttons return to simulation.");
                      }
                    }));
  }

  private void exportReadings() {
    if (engine == null) return;
    List<Snapshot> captured = engine.history();
    if (captured.isEmpty()) {
      toast("Waiting for measurements to export.");
      return;
    }
    StringBuilder csv = new StringBuilder("tick,sensor,value\n");
    for (var s : captured)
      for (var r : s.sensors())
        csv.append(s.tick())
            .append(',')
            .append(r.id())
            .append(',')
            .append(Double.isFinite(r.raw()) ? r.raw() : "NaN")
            .append('\n');
    save("bridgetrust-readings.csv", csv.toString());
  }

  private void exportEvents() {
    if (engine.latest() == null) return;
    StringBuilder csv = new StringBuilder("time,tick,sensor,verdict,evidence,reviewed\n");
    for (var e : engine.latest().events())
      csv.append(e.time())
          .append(',')
          .append(e.tick())
          .append(',')
          .append(e.sensor())
          .append(',')
          .append(e.verdict())
          .append(',')
          .append(FileStorage.csv(e.detail()))
          .append(',')
          .append(acknowledged.contains(eventKey(e)))
          .append('\n');
    save("bridgetrust-events.csv", csv.toString());
  }

  private void save(String name, String content) {
    FileChooser chooser = new FileChooser();
    chooser.setInitialFileName(name);
    chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
    var file = chooser.showSaveDialog(stage);
    if (file == null) return;
    CompletableFuture.runAsync(
            () -> {
              try {
                Files.writeString(file.toPath(), content);
              } catch (Exception ex) {
                throw new CompletionException(ex);
              }
            },
            tasks)
        .whenComplete(
            (v, error) ->
                Platform.runLater(
                    () -> {
                      if (error != null) error("Export failed", error);
                      else toast("Saved " + file.getName());
                    }));
  }

  private void runBenchmark() {
    var button = (Button) pages.get("Scenario lab").lookup("#run-benchmark");
    button.setDisable(true);
    benchmarkResults
        .getChildren()
        .setAll(label("Evaluating eight reproducible scenarios…", "muted"));
    CompletableFuture.supplyAsync(Benchmark::run, tasks)
        .whenComplete(
            (results, error) ->
                Platform.runLater(
                    () -> {
                      button.setDisable(false);
                      if (error != null) {
                        error("Evaluation failed", error);
                        return;
                      }
                      benchmark = results;
                      TableView<Benchmark.Result> table =
                          new TableView<>(FXCollections.observableArrayList(results));
                      table.setColumnResizePolicy(
                          TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
                      table.setPrefHeight(325);
                      table.getColumns().add(column("SCENARIO", Benchmark.Result::scenario, 175));
                      table.getColumns().add(column("DETECTION", r -> pct(r.recall()), 110));
                      table.getColumns().add(column("CLASSIFICATION", r -> pct(r.accuracy()), 130));
                      table
                          .getColumns()
                          .add(column("FALSE ALARMS", r -> pct(r.falseAlarmRate()), 125));
                      table.getColumns().add(column("ENGINE RMSE", r -> fmt(r.engineRmse()), 125));
                      table.getColumns().add(column("MEAN RMSE", r -> fmt(r.meanRmse()), 115));
                      table.getColumns().add(column("MEDIAN RMSE", r -> fmt(r.medianRmse()), 130));
                      benchmarkResults
                          .getChildren()
                          .setAll(
                              table,
                              button(
                                  "Export evaluation  ↗",
                                  "secondary-button",
                                  () ->
                                      save("bridgetrust-evaluation.csv", Benchmark.csv(benchmark))),
                              label(
                                  "Rates use labelled sensor samples. RMSE uses the spatial mean of"
                                      + " genuine strain, including damage; units µε. Results"
                                      + " describe this simulator only.",
                                  "small-muted"));
                    }));
  }

  private Node footer() {
    notice.managedProperty().bind(notice.textProperty().isNotEmpty());
    notice.visibleProperty().bind(notice.managedProperty());
    return new VBox(
        8,
        notice,
        row(
            status,
            stretch(),
            label("AMBIENT", "eyebrow"),
            tempLabel,
            label("VALIDATION", "eyebrow"),
            latency));
  }

  private void toast(String message) {
    notice.setText(message);
    PauseTransition delay = new PauseTransition(Duration.seconds(6));
    delay.setOnFinished(
        e -> {
          if (notice.getText().equals(message)) notice.setText("");
        });
    delay.play();
  }

  private void error(String title, Throwable error) {
    Throwable cause = error;
    while (cause.getCause() != null) cause = cause.getCause();
    Alert alert = new Alert(Alert.AlertType.ERROR, cause.getMessage(), ButtonType.OK);
    alert.initOwner(stage);
    alert.setHeaderText(title);
    alert.show();
  }

  private VBox page(String title, String subtitle, String index) {
    VBox page = new VBox(13);
    page.getStyleClass().add("page");
    page.getChildren()
        .addAll(
            new VBox(
                5,
                row(label(title, "page-title"), stretch(), label(index, "eyebrow")),
                label(subtitle, "page-subtitle")));
    return page;
  }

  private Node metric(String title, Label value, Label detail, String unit) {
    VBox box = card();
    box.getStyleClass().add("metric");
    box.setMaxWidth(Double.MAX_VALUE);
    box.getChildren()
        .addAll(label(title, "eyebrow"), row(value, label(unit, "metric-unit")), detail);
    return box;
  }

  private ScrollPane scroll(Node node) {
    ScrollPane scroll = new ScrollPane(node);
    scroll.setFitToWidth(true);
    scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    scroll.getStyleClass().add("page-scroll");
    return scroll;
  }

  private static VBox card() {
    VBox box = new VBox(12);
    box.getStyleClass().add("card");
    return box;
  }

  private static Label label(String text, String style) {
    Label label = new Label(text);
    label.getStyleClass().add(style);
    if (style.equals("body-copy") || style.equals("small-muted")) label.setWrapText(true);
    return label;
  }

  private static HBox row(Node... nodes) {
    HBox row = new HBox(10, nodes);
    row.setAlignment(Pos.CENTER_LEFT);
    return row;
  }

  private static Region stretch() {
    Region r = new Region();
    HBox.setHgrow(r, Priority.ALWAYS);
    return r;
  }

  private static Region spacer(double height) {
    Region r = new Region();
    r.setMinHeight(height);
    return r;
  }

  private static Button button(String text, String style, Runnable action) {
    Button b = new Button(text);
    b.getStyleClass().add(style);
    b.setOnAction(e -> action.run());
    return b;
  }

  private static SVGPath icon(String path) {
    SVGPath icon = new SVGPath();
    icon.setContent(path);
    icon.getStyleClass().add("icon");
    return icon;
  }

  private static <T> TableColumn<T, String> column(
      String title, Function<T, String> value, double width) {
    TableColumn<T, String> column = new TableColumn<>(title);
    column.setCellValueFactory(c -> new ReadOnlyStringWrapper(value.apply(c.getValue())));
    column.setPrefWidth(width);
    return column;
  }

  private static String fmt(double value) {
    return Double.isFinite(value) ? String.format(Locale.ROOT, "%.2f", value) : "—";
  }

  private static String pct(double value) {
    return Double.isFinite(value) ? String.format(Locale.ROOT, "%.1f%%", value) : "n/a";
  }

  private static String time(Instant time) {
    return DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(time);
  }

  @Override
  public void stop() {
    stopped = true;
    if (refresh != null) refresh.stop();
    if (simulator != null) simulator.close();
    if (engine != null) engine.close();
    else if (store != null) store.close();
    tasks.shutdownNow();
  }

  private void smoke() {
    // Opt-in integration harness: operate actual controls, then capture the actual JavaFX scene.
    try {
      Files.deleteIfExists(Path.of("target/ui-smoke.txt"));
    } catch (java.io.IOException ex) {
      throw new java.io.UncheckedIOException(ex);
    }
    Timeline test =
        new Timeline(
            new KeyFrame(
                Duration.seconds(3),
                e -> {
                  show("Scenario lab");
                  scenarioButtons.get(Simulator.Scenario.DAMAGE).fire();
                }),
            new KeyFrame(
                Duration.seconds(13),
                e -> {
                  selectSensor("S2");
                  show("Overview");
                  simulator.setPaused(true);
                }),
            new KeyFrame(
                Duration.seconds(14),
                e -> {
                  try {
                    Snapshot snapshot = engine.latest();
                    if (snapshot == null || snapshot.sensor("S2").verdict() != Verdict.STRUCTURAL)
                      throw new IllegalStateException(
                          "Scenario interaction did not produce structural evidence");
                    capture("observatory-smoke");
                  } catch (Exception ex) {
                    ex.printStackTrace();
                    System.setProperty("bridgetrust.smoke.failed", "true");
                  }
                }),
            new KeyFrame(Duration.seconds(15), e -> navigation.get("Sensor network").fire()),
            new KeyFrame(Duration.seconds(16), e -> capture("network-smoke")),
            new KeyFrame(
                Duration.seconds(17),
                e -> {
                  navigation.get("Scenario lab").fire();
                  ((Button) pages.get("Scenario lab").lookup("#run-benchmark")).fire();
                }),
            new KeyFrame(
                Duration.seconds(19),
                e -> {
                  capture("laboratory-smoke");
                  if (benchmark.size() != 8) System.setProperty("bridgetrust.smoke.failed", "true");
                  navigation.get("Event journal").fire();
                  auditTable.getSelectionModel().selectFirst();
                  acknowledge();
                }),
            new KeyFrame(
                Duration.seconds(20),
                e -> {
                  capture("journal-smoke");
                  navigation.get("Overview").fire();
                  stage.setWidth(1180);
                  stage.setHeight(820);
                }),
            new KeyFrame(
                Duration.seconds(22),
                e -> {
                  capture("compact-smoke");
                  try {
                    if (!Boolean.getBoolean("bridgetrust.smoke.failed")) {
                      Files.writeString(
                          Path.of("target/ui-smoke.txt"),
                          "PASS: native startup, scenario control, structural verdict, sensor"
                              + " selection, pause, all four workspaces, benchmark, acknowledgement"
                              + " and compact layout\n");
                      System.out.println("UI SMOKE PASS");
                    }
                  } catch (Exception ex) {
                    ex.printStackTrace();
                    System.setProperty("bridgetrust.smoke.failed", "true");
                  } finally {
                    Platform.exit();
                  }
                }));
    test.play();
  }

  private void capture(String name) {
    try {
      Files.createDirectories(Path.of("target"));
      root.applyCss();
      root.layout();
      WritableImage image = root.snapshot(null, null);
      int w = (int) image.getWidth(), h = (int) image.getHeight();
      BufferedImage png = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
      for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
      ImageIO.write(png, "png", Path.of("target/" + name + ".png").toFile());
    } catch (Exception ex) {
      ex.printStackTrace();
      System.setProperty("bridgetrust.smoke.failed", "true");
    }
  }
}
