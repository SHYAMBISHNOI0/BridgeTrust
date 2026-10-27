package bridgetrust.stages;

import bridgetrust.model.Evidence;
import java.util.List;

public interface ValidationStage {
  record Context(
      String id, double raw, double residual, boolean temperatureValid, List<Double> history) {}

  Evidence check(Context context);
}
