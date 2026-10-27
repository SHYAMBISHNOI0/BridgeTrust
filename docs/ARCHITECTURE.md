# BridgeTrust architecture

```text
Simulation clock / validated CSV replay
  ├─ sensor-S1 … sensor-S5 (one executor per feed)
  └─ sensor-T1
          │ immutable Reading
          ▼
ArrayBlockingQueue (512)
          │
Frame coordinator (ordered, duplicate/late rejection, 750 ms deadline)
          ├─ west validation node: S1–S3
          └─ east validation node: S4–S5
          │ thermal reference validated before strain
          ▼
Physical coherence → temporal confirmation → trust → fusion
          │ immutable Snapshot
          ├─ AtomicReference → JavaFX (100 ms refresh, 2 Hz acquisition)
          ├─ bounded history: 360 frames / 200 transitions
          └─ audit queue (256) → local CSV + atomic trust → optional JDBC transaction
```

## Invariants

- No JavaFX control is mutated by a worker thread. Long-running evaluation and file operations use a desktop I/O executor.
- A frame is published only after both validation workers finish. Histories and CUSUM instances are exclusive to their sensor; frame processing remains ordered.
- A late message never revises an already published frame. Incomplete frames become explicit missing measurements, not infinite pending work. At most eight pending frame buckets force flushing under irregular input.
- The classifier has no access to simulator mode, injected labels, or truth. The evaluator owns ground truth independently.
- Neighbour reconstruction only uses current eligible measured neighbours. It does not recurse through other reconstructions and is not added as independent evidence in fusion.
- Estimated values and intervals may be unavailable. NaN becomes a missing cell/SQL NULL, never a numeric zero.
- The first suspicious soft frame is questionable and excluded from fusion; persistent adjacent coherent anomalies are preserved as structural measurements.
- JDBC failures roll back the entire frame. Local audit continues if JDBC fails. A failed/full local audit path stops new processing; it cannot silently claim a complete archive.
- Configuration uses environment variables, never hard-coded passwords. There is no listening HTTP port.

## Statistics and limitations

The network median provides the robust reference; physical adjacency determines whether deviations corroborate one another. Range bounds are −100…100 µε and −40…85°C. Exact repeated raw values over six frames trigger stuck detection. Neighbour residuals over 1.05 µε and two-sided CUSUM over 3.0 trigger suspicion. Adjacent residuals must share direction and differ by less than 1.4 µε to be coherent. These are simulation calibration values.

Fault trust decays by a factor of 0.72 per frame. Healthy/structural observations recover 12% of the remaining distance to 1.0. Questionable states hold trust steady. Recovery remains questionable until trust reaches 0.8.

Fusion computes the weighted mean of accepted compensated measurements. The approximate 95% half-width uses unbiased weighted sample variance, a 0.16 µε noise floor, and Kish effective sample size. With one independent sensor, the value is available but the interval is not. A bad thermal reference invalidates all compensated strain output until restored.

The model cannot resolve common-mode sensor faults versus genuine global load or damage without additional physical constraints/reference sensors. Majority shifts can contaminate the median. Independent, identical faults on adjacent sensors may resemble damage. The UI presents evidence and leaves engineering interpretation to the operator.

## Recovery and storage

The local trust checkpoint is the authoritative restart state, including when MySQL is enabled. A corrupt or incompatible checkpoint is rejected and logged; trust starts at 1.0. Legacy prototype checkpoints are intentionally not consumed.

The audit writer flushes CSV after each frame and atomically replaces the trust checkpoint. This protects against partially replaced checkpoints, but it is not an fsync-backed write-ahead log or a claim of zero loss after power failure. Filesystem flush and the three CSV files are not a single transaction. MySQL frame writes are transactional.

The database mirror disables itself on a write failure and reports degradation. There is no automatic reconnect or exactly-once remote backfill. Session UUID and tick/sensor primary keys prevent collisions; local records are retained for explicit recovery. Archive retention is operator-managed. Review acknowledgement is UI-session state, distinct from immutable event evidence.

## Desktop and release

The bridge is built from procedural 3D vertices, shaded faces, suspension curves and traffic geometry. The renderer rotates world coordinates and sorts faces by camera depth onto JavaFX Canvas. This provides interactive orbit/top views without platform-dependent GPU requirements. Sensor hit targets are projected from the same world coordinates. Geometry is schematic; it does not claim a structural finite-element model.

JavaFX styles live in [observatory.css](../src/main/resources/bridgetrust/ui/observatory.css). No web content is embedded. Native app images bundle the Java runtime with `jpackage`; distribution installers must be built and signed on each operating system.
