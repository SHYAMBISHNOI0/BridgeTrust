package bridgetrust.store;

import bridgetrust.model.Snapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Local durable audit first, optional JDBC mirror second. I/O never runs on JavaFX. */
public final class AuditStore implements Storage {
  private record Job(Snapshot snapshot, Map<String, Double> trust) {}

  private final FileStorage local;
  private JdbcStorage jdbc;
  private final BlockingQueue<Job> queue = new ArrayBlockingQueue<>(256);
  private final Thread writer;
  private volatile boolean running = true;
  private volatile String status = "Local audit active";

  public AuditStore(Path root) throws IOException {
    String session = UUID.randomUUID().toString();
    local = new FileStorage(root, session);
    String url = System.getenv("BT_DB_URL");
    if (url != null && !url.isBlank()) {
      try {
        jdbc =
            new JdbcStorage(url, System.getenv("BT_DB_USER"), System.getenv("BT_DB_PASS"), session);
        status = "MySQL + local audit";
      } catch (Exception ex) {
        status = "MySQL unavailable · local audit active";
        System.err.println(status + " (" + ex.getClass().getSimpleName() + ")");
      }
    }
    writer = new Thread(this::write, "audit-writer");
    writer.start();
  }

  public void save(Snapshot snapshot, Map<String, Double> trust) {
    if (!running)
      throw new IllegalStateException(
          "Audit writer is unavailable; refusing unrecorded processing");
    if (!queue.offer(new Job(snapshot, Map.copyOf(trust)))) {
      status = "AUDIT OVERFLOW · processing stopped";
      throw new IllegalStateException("Audit queue full; refusing unrecorded processing");
    }
  }

  private void write() {
    try {
      while (running || !queue.isEmpty()) {
        Job job = queue.poll(100, TimeUnit.MILLISECONDS);
        if (job == null) continue;
        try {
          local.save(job.snapshot(), job.trust());
        } catch (RuntimeException ex) {
          status = "LOCAL AUDIT FAILED · " + ex.getClass().getSimpleName();
          running = false;
          return;
        }
        if (jdbc != null)
          try {
            jdbc.save(job.snapshot(), job.trust());
          } catch (RuntimeException ex) {
            jdbc.close();
            jdbc = null;
            status = "MySQL disconnected · local audit active";
          }
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    } finally {
      local.close();
      if (jdbc != null) jdbc.close();
    }
  }

  public Map<String, Double> loadTrust() {
    return local.loadTrust();
  }

  public String status() {
    return status;
  }

  public Path sessionDirectory() {
    return local.sessionDirectory();
  }

  public void close() {
    running = false;
    try {
      writer.join(10_000);
      if (writer.isAlive()) {
        status = "Audit drain timed out";
        writer.interrupt();
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }
}
