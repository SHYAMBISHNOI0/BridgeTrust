package bridgetrust;

import bridgetrust.engine.Benchmark;
import bridgetrust.ui.ObservatoryApp;
import javafx.application.Application;

public final class Main {
  private Main() {}

  public static void main(String[] args) {
    if (args.length > 0 && args[0].equals("--benchmark")) {
      System.out.print(Benchmark.csv(Benchmark.run()));
      return;
    }
    Application.launch(ObservatoryApp.class, args);
    if (Boolean.getBoolean("bridgetrust.smoke.failed")) System.exit(1);
  }
}
