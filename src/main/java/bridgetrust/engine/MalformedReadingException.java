package bridgetrust.engine;

/** MODULE 3: custom checked exception so one bad packet never crashes the node. */
public class MalformedReadingException extends java.io.IOException {
  public MalformedReadingException(String m) {
    super(m);
  }
}
