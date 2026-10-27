package bridgetrust;

import static org.junit.jupiter.api.Assertions.*;

import bridgetrust.engine.Validator;
import bridgetrust.store.JdbcStorage;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in test against a dedicated MySQL test database, never an embedded substitute. */
@EnabledIfEnvironmentVariable(named = "BT_TEST_DB_URL", matches = ".+")
class JdbcIntegrationTest {
  @Test
  void frameAndEvidencePersistAndDuplicateTransactionRollsBack() throws Exception {
    String url = System.getenv("BT_TEST_DB_URL"),
        user = System.getenv("BT_TEST_DB_USER"),
        password = System.getenv("BT_TEST_DB_PASS"),
        session = UUID.randomUUID().toString();
    try (var storage = new JdbcStorage(url, user, password, session);
        var validator = new Validator(Map.of(), false)) {
      Map<String, Double> values =
          Map.of("S1", 24.0, "S2", 24.1, "S3", Double.NaN, "S4", 24.2, "S5", 24.3, "T1", 15.1);
      var snapshot = validator.process(0, values, 0);
      storage.save(snapshot, validator.trust());
      assertThrows(IllegalStateException.class, () -> storage.save(snapshot, validator.trust()));
      storage.save(validator.process(1, values, 0), validator.trust());
      try (var connection = DriverManager.getConnection(url, user, password);
          var query =
              connection.prepareStatement(
                  "SELECT raw_value,evidence FROM bt_readings WHERE session_id=? AND tick=0 AND"
                      + " sensor='S3'")) {
        query.setString(1, session);
        try (var result = query.executeQuery()) {
          assertTrue(result.next());
          assertNull(result.getObject(1));
          assertTrue(result.getString(2).contains("FAIL"));
        }
      }
    } finally {
      try (var connection = DriverManager.getConnection(url, user, password)) {
        for (String table : List.of("bt_readings", "bt_estimates", "bt_events"))
          try (var delete =
              connection.prepareStatement("DELETE FROM " + table + " WHERE session_id=?")) {
            delete.setString(1, session);
            delete.executeUpdate();
          }
      }
    }
  }
}
