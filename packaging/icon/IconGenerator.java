import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Genere l'icone de l'application (PNG + ICO multi-tailles) sans dependance.
 * Usage : java packaging/icon/IconGenerator.java packaging
 * Produit packaging/windows/financeapp.ico et packaging/linux/financeapp.png.
 */
public class IconGenerator {

    static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "packaging");
        List<byte[]> pngs = new ArrayList<>();
        for (int size : SIZES) {
            pngs.add(png(draw(size)));
        }
        Files.write(out.resolve("linux/financeapp.png"), png(draw(256)));
        Files.write(out.resolve("windows/financeapp.ico"), ico(pngs));
    }

    static BufferedImage draw(int s) {
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        double m = s * 0.04;
        double r = s * 0.22;
        g.setPaint(new GradientPaint(0, 0, new Color(0x1d2d4d), s, s, new Color(0x4c8dff)));
        g.fill(new RoundRectangle2D.Double(m, m, s - 2 * m, s - 2 * m, r, r));

        // Courbe de solde montante (le produit : "combien vais-je avoir ?")
        Path2D line = new Path2D.Double();
        line.moveTo(s * 0.20, s * 0.68);
        line.lineTo(s * 0.40, s * 0.50);
        line.lineTo(s * 0.55, s * 0.60);
        line.lineTo(s * 0.78, s * 0.32);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke((float) Math.max(1.5, s * 0.075), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(line);
        double d = Math.max(3, s * 0.16);
        g.setColor(new Color(0x3ecf8e));
        g.fill(new Ellipse2D.Double(s * 0.78 - d / 2, s * 0.32 - d / 2, d, d));
        g.dispose();
        return img;
    }

    static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bytes);
        return bytes.toByteArray();
    }

    /** Conteneur ICO dont chaque entree est un PNG (pris en charge depuis Windows Vista). */
    static byte[] ico(List<byte[]> pngs) {
        int header = 6 + 16 * pngs.size();
        int total = header + pngs.stream().mapToInt(p -> p.length).sum();
        ByteBuffer b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 0).putShort((short) 1).putShort((short) pngs.size());
        int offset = header;
        for (int i = 0; i < pngs.size(); i++) {
            int size = SIZES[i];
            b.put((byte) (size >= 256 ? 0 : size)).put((byte) (size >= 256 ? 0 : size))
                    .put((byte) 0).put((byte) 0)
                    .putShort((short) 1).putShort((short) 32)
                    .putInt(pngs.get(i).length).putInt(offset);
            offset += pngs.get(i).length;
        }
        for (byte[] p : pngs) {
            b.put(p);
        }
        return b.array();
    }
}
