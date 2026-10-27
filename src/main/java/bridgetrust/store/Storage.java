package bridgetrust.store;

import bridgetrust.model.Snapshot;
import java.util.Map;

public interface Storage extends AutoCloseable {
  void save(Snapshot snapshot, Map<String, Double> trust);

  Map<String, Double> loadTrust();

  String status();

  void close();
}
