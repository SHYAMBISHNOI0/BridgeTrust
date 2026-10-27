package bridgetrust.store;

import bridgetrust.model.Snapshot;
import java.sql.*;
import java.util.*;

/** JDBC only. Each frame, evidence and trust update commits as one MySQL transaction. */
public final class JdbcStorage implements Storage {
  private final Connection connection;
  private final String session;
  private int lastEventTick = -1;

  public JdbcStorage(String url, String user, String password, String session) throws SQLException {
    this.session = session;
    Properties properties = new Properties();
    properties.setProperty("user", user == null ? "" : user);
    properties.setProperty("password", password == null ? "" : password);
    properties.setProperty("connectTimeout", "3000");
    properties.setProperty("socketTimeout", "5000");
    connection = DriverManager.getConnection(url, properties);
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE IF NOT EXISTS bt_readings (session_id VARCHAR(36), tick INT, sensor"
              + " VARCHAR(8), ts TIMESTAMP(3), raw_value DOUBLE NULL, compensated DOUBLE NULL,"
              + " trust DOUBLE NOT NULL, verdict VARCHAR(24), reconstructed DOUBLE NULL, evidence"
              + " TEXT, PRIMARY KEY(session_id,tick,sensor))");
      statement.execute(
          "CREATE TABLE IF NOT EXISTS bt_estimates (session_id VARCHAR(36), tick INT, estimate"
              + " DOUBLE NULL, ci95 DOUBLE NULL, effective_sensors DOUBLE, contributors INT,"
              + " plain_mean DOUBLE NULL, median_discard DOUBLE NULL, PRIMARY"
              + " KEY(session_id,tick))");
      statement.execute(
          "CREATE TABLE IF NOT EXISTS bt_events (session_id VARCHAR(36), tick INT, sensor"
              + " VARCHAR(8), ts TIMESTAMP(3), verdict VARCHAR(24), evidence TEXT, PRIMARY"
              + " KEY(session_id,tick,sensor))");
      statement.execute(
          "CREATE TABLE IF NOT EXISTS bt_trust (sensor VARCHAR(8) PRIMARY KEY, score DOUBLE NOT"
              + " NULL)");
    } catch (SQLException ex) {
      connection.close();
      throw ex;
    }
    connection.setAutoCommit(false);
  }

  public synchronized void save(Snapshot snapshot, Map<String, Double> trust) {
    try (PreparedStatement reading =
            connection.prepareStatement("INSERT INTO bt_readings VALUES(?,?,?,?,?,?,?,?,?,?)");
        PreparedStatement estimate =
            connection.prepareStatement("INSERT INTO bt_estimates VALUES(?,?,?,?,?,?,?,?)");
        PreparedStatement event =
            connection.prepareStatement("INSERT INTO bt_events VALUES(?,?,?,?,?,?)");
        PreparedStatement score =
            connection.prepareStatement(
                "INSERT INTO bt_trust(sensor,score) VALUES(?,?) ON DUPLICATE KEY UPDATE score=?")) {
      for (var s : snapshot.sensors()) {
        reading.setString(1, session);
        reading.setInt(2, snapshot.tick());
        reading.setString(3, s.id());
        reading.setTimestamp(4, Timestamp.from(snapshot.time()));
        value(reading, 5, s.raw());
        value(reading, 6, s.compensated());
        reading.setDouble(7, s.trust());
        reading.setString(8, s.verdict().name());
        value(reading, 9, s.reconstructed());
        reading.setString(10, s.explanation() + " | " + s.evidence());
        reading.addBatch();
      }
      reading.executeBatch();
      var e = snapshot.estimate();
      estimate.setString(1, session);
      estimate.setInt(2, snapshot.tick());
      value(estimate, 3, e.value());
      value(estimate, 4, e.halfWidth());
      estimate.setDouble(5, e.effectiveSensors());
      estimate.setInt(6, e.contributors());
      value(estimate, 7, snapshot.plainMean());
      value(estimate, 8, snapshot.medianDiscard());
      estimate.executeUpdate();
      for (var ev : snapshot.events())
        if (ev.tick() > lastEventTick) {
          event.setString(1, session);
          event.setInt(2, ev.tick());
          event.setString(3, ev.sensor());
          event.setTimestamp(4, Timestamp.from(ev.time()));
          event.setString(5, ev.verdict().name());
          event.setString(6, ev.detail());
          event.addBatch();
        }
      event.executeBatch();
      for (var t : trust.entrySet()) {
        score.setString(1, t.getKey());
        score.setDouble(2, t.getValue());
        score.setDouble(3, t.getValue());
        score.addBatch();
      }
      score.executeBatch();
      connection.commit();
      lastEventTick = snapshot.tick();
    } catch (SQLException ex) {
      try {
        connection.rollback();
      } catch (SQLException rollback) {
        ex.addSuppressed(rollback);
      }
      throw new IllegalStateException(
          "JDBC transaction failed (SQLState " + ex.getSQLState() + ")", ex);
    }
  }

  private static void value(PreparedStatement p, int index, double v) throws SQLException {
    if (Double.isFinite(v)) p.setDouble(index, v);
    else p.setNull(index, Types.DOUBLE);
  }

  public synchronized Map<String, Double> loadTrust() {
    Map<String, Double> scores = new HashMap<>();
    try (var statement = connection.createStatement();
        var result = statement.executeQuery("SELECT sensor,score FROM bt_trust")) {
      while (result.next()) scores.put(result.getString(1), result.getDouble(2));
      connection.commit();
    } catch (SQLException ex) {
      throw new IllegalStateException("Unable to restore JDBC trust", ex);
    }
    return scores;
  }

  public String status() {
    return "MySQL connected";
  }

  public synchronized void close() {
    try {
      connection.close();
    } catch (SQLException ex) {
      System.err.println("JDBC close failed");
    }
  }
}
