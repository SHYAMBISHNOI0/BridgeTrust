# Verification record

Verified on macOS / Apple Silicon with JDK 21.0.7, JavaFX 21.0.7 and Maven 3.9.9, 19 October 2026.

## Automated checks

- **13 tests pass**, zero failures/errors.
- **1 live MySQL test is skipped** because `BT_TEST_DB_URL` is not configured. JDBC compiles and Connector/J is packaged; an actual MySQL connection and transaction round trip are not claimed as verified.
- The native JavaFX smoke harness passes startup, scenario-button activation, a confirmed structural verdict, sensor selection, pause, navigation across all four workspaces, reproducible evaluation and review acknowledgement.
- Actual JavaFX scene snapshots are inspected at desktop and compact window sizes. No HTML mockup is substituted for native rendering.
- A macOS ARM64 application image is built with `jpackage` and its bundled runtime. The native application launcher is exercised with the same end-to-end smoke harness.

To run the optional database test, use a dedicated disposable MySQL database:

```sh
BT_TEST_DB_URL='jdbc:mysql://localhost:3306/bridgetrust_test' \
BT_TEST_DB_USER='bridgetrust_test' BT_TEST_DB_PASS='your-password' \
mvn -Dtest=JdbcIntegrationTest test
```

The integration test creates the application tables, writes test trust scores, verifies SQL NULL/evidence and rollback behaviour, and removes its UUID-scoped measurement/event records. It must not target a production database.

## Reproducible evaluation

Eight scenarios × 200 frames; only frames 65–199 are scored. Seeds are fixed. The scoring window excludes warm-up and onset latency, so these rates do not measure immediate detection. Each scenario has 135 evaluated frames. RMSE is in microstrain.

| Scenario | Fault/damage classification | False alarms | BridgeTrust RMSE | Mean RMSE | Median-discard RMSE |
|---|---:|---:|---:|---:|---:|
| Normal traffic | n/a | 0.0% | 0.0691 | 0.0691 | 0.0691 |
| Sensor spike | 100.0% | 0.0% | 0.0753 | 7.0019 | 0.0753 |
| Frozen sensor | 100.0% | 0.0% | 0.0853 | 0.4111 | 0.1039 |
| Gradual drift | 100.0% | 0.0% | 0.0786 | 1.9851 | 0.0786 |
| Signal dropout | 100.0% | 0.0% | 0.0753 | 0.0753 | 0.0753 |
| Structural shift | 100.0% | 0.0% | 0.0691 | 0.0691 | 2.4068 |
| Thermal cycle | n/a | 0.0% | 0.0691 | 0.0691 | 0.0691 |
| Malformed input | 100.0% | 0.0% | 0.0740 | 0.0740 | 0.0740 |

These are measured results for deliberately separated simulated cases, not general accuracy estimates for real bridges. Classification is scored only on anomalous sensor samples; false alarms are scored on healthy samples. Detection recall equals classification accuracy in this run because all detected anomalies received the correct simulated class. There is no advantage over baselines in scenarios where those baselines already perform adequately.

For the structural case, genuine strain includes the shift. The median-discard baseline suppresses that change and therefore has larger error. Both the plain mean and BridgeTrust retain the genuine spatial mean. In the spike case, plain mean is biased while BridgeTrust excludes the faulty reading.

The complete machine-readable results are in [evaluation.csv](evaluation.csv).

## Remaining external validation

- Live MySQL environment and operational reconnect/backfill workflow.
- Windows/Linux native UI and distribution builds; this run verifies macOS ARM64 only.
- Signed/notarized installers; `jpackage` produces a local application image.
- Real-world sensor calibration, labelled recorded bridge datasets, common-mode/correlated faults, uncertainty calibration, and independent structural safety assessment.

Known model limits and persistence guarantees are detailed in [architecture](ARCHITECTURE.md). Setup and operation are in the [README](../README.md).
