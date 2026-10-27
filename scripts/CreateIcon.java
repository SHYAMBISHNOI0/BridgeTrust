import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import javax.imageio.ImageIO;

/** Reproducible original application icon, drawn entirely in Java2D. */
class CreateIcon {
  static byte[] png(int size) throws IOException {
    BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = image.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.scale(size / 1024.0, size / 1024.0);
    g.setColor(new Color(20, 31, 37));
    g.fill(new RoundRectangle2D.Double(48, 48, 928, 928, 200, 200));
    g.setColor(new Color(54, 72, 79));
    g.setStroke(new BasicStroke(8));
    g.draw(new RoundRectangle2D.Double(52, 52, 920, 920, 198, 198));
    g.setColor(new Color(225, 181, 140));
    g.setStroke(new BasicStroke(30, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    g.drawLine(292, 300, 292, 700);
    g.drawLine(732, 300, 732, 700);
    g.drawLine(172, 644, 852, 644);
    Path2D cable = new Path2D.Double();
    cable.moveTo(172, 565);
    cable.lineTo(292, 322);
    cable.curveTo(410, 565, 614, 565, 732, 322);
    cable.lineTo(852, 565);
    g.draw(cable);
    g.setStroke(new BasicStroke(12));
    for (int x = 365; x < 700; x += 73) {
      double u = (x - 292) / 440.0;
      double y = 322 + 243 * 3 * u * (1 - u);
      g.drawLine(x, (int) y, x, 644);
    }
    g.setColor(new Color(129, 200, 172));
    for (int x : new int[] {292, 512, 732}) g.fillOval(x - 24, 700, 48, 48);
    g.dispose();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    return out.toByteArray();
  }

  public static void main(String[] args) throws IOException {
    Files.createDirectories(Path.of("packaging"));
    Files.write(Path.of("src/main/resources/bridgetrust/ui/app-icon.png"), png(256));
    ByteArrayOutputStream chunks = new ByteArrayOutputStream();
    DataOutputStream data = new DataOutputStream(chunks);
    int[] sizes = {128, 256, 512, 1024};
    String[] types = {"ic07", "ic08", "ic09", "ic10"};
    for (int i = 0; i < sizes.length; i++) {
      byte[] image = png(sizes[i]);
      data.writeBytes(types[i]);
      data.writeInt(image.length + 8);
      data.write(image);
    }
    try (DataOutputStream out =
        new DataOutputStream(Files.newOutputStream(Path.of("packaging/bridgetrust.icns")))) {
      out.writeBytes("icns");
      out.writeInt(chunks.size() + 8);
      chunks.writeTo(out);
    }
    byte[] image = png(256);
    ByteBuffer ico = ByteBuffer.allocate(22 + image.length).order(ByteOrder.LITTLE_ENDIAN);
    ico.putShort((short) 0)
        .putShort((short) 1)
        .putShort((short) 1)
        .putInt(0)
        .putShort((short) 1)
        .putShort((short) 32)
        .putInt(image.length)
        .putInt(22)
        .put(image);
    Files.write(Path.of("packaging/bridgetrust.ico"), ico.array());
  }
}
