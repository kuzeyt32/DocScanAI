import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.imageio.ImageIO;

/**
 * DocScanAI SUBTASK H3a - host-side benchmark artifact packager.
 *
 * Takes the artifacts the corpus lane already pulled from the device
 * (`<run>/corpus_out/{ai_on,ai_off}/...`) and lays them out exactly as the subtask requires:
 *
 *   <run>/benchmark/{ai_on,ai_off}/<name>/{input.jpg,raw.jpg,page.jpg,thumb.jpg,gt_scan.jpg,docscan.txt}
 *   <run>/benchmark_report.json            merged per-image rows for the benchmark group
 *   <run>/benchmark_report.csv
 *   <run>/overlay/{ai_on,ai_off}/<name>.png annotated detected-quad overlays
 *   <run>/overlay_compare/<name>.png      INPUT+quad(AI ON) | INPUT+quad(AI OFF) | AI-ON page | AI-OFF page
 *
 * DIAGNOSTIC ONLY: it copies and composes images; it never touches production output.
 *
 * JDK 21 only (javax.imageio + java.awt). Run:
 *   "C:\Program Files\Java\jdk-21\bin\java.exe" tooling\BenchmarkArtifacts.java . h3a_20261016_1714
 */
public class BenchmarkArtifacts {

    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int CELL_W = 620;
    private static final int CELL_H = 780;
    private static final int CAP_H = 46;
    private static final int HEADER_H = 74;
    private static final int MARGIN = 16;

    private static final Color C_ON = new Color(0xA02020);
    private static final Color C_OFF = new Color(0x1B6B33);
    private static final Color C_IN = new Color(0x1B3A6B);

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        String runId = args.length > 1 ? args[1] : null;
        if (runId == null) {
            System.err.println("usage: BenchmarkArtifacts <projectRoot> <runId>");
            System.exit(2);
        }
        Path run = root.resolve("tooling/out").resolve(runId);
        Path corpusOut = run.resolve("corpus_out");
        if (!Files.isDirectory(corpusOut)) {
            log("FATAL: " + corpusOut + " not found - pull the corpus_out folder first");
            System.exit(2);
        }
        Path bench = run.resolve("benchmark");
        Path overlay = run.resolve("overlay");
        Path compare = run.resolve("overlay_compare");
        for (Path d : Arrays.asList(bench, overlay, compare,
                bench.resolve("ai_on"), bench.resolve("ai_off"),
                overlay.resolve("ai_on"), overlay.resolve("ai_off"))) {
            Files.createDirectories(d);
        }

        // The BENCHMARK group only: the lane writes a benchmark-only CSV per mode, so its image
        // column is the authoritative member list. Fall back to the full folder listing if absent.
        List<String> names = benchmarkNames(corpusOut);
        if (names.isEmpty()) {
            names = listImages(corpusOut.resolve("ai_on/images"));
            log("  (no benchmark_report_ai_on.csv - falling back to ALL corpus images)");
        }
        log("benchmark images found: " + names.size() + " " + names);

        int copied = 0, overlays = 0, strips = 0;
        List<String> problems = new ArrayList<>();
        for (String name : names) {
            for (String mode : new String[]{"ai_on", "ai_off"}) {
                Path src = corpusOut.resolve(mode).resolve("images").resolve(name);
                Path dst = bench.resolve(mode).resolve(name);
                Files.createDirectories(dst);
                for (String f : new String[]{"input.jpg", "raw.jpg", "page.jpg", "thumb.jpg", "gt_scan.jpg", "docscan.txt"}) {
                    Path s = src.resolve(f);
                    if (Files.isRegularFile(s)) {
                        Files.copy(s, dst.resolve(f), StandardCopyOption.REPLACE_EXISTING);
                        copied++;
                    } else if (f.equals("page.jpg") || f.equals("input.jpg")) {
                        problems.add(name + "/" + mode + ": missing " + f);
                    }
                }
                Path ov = src.resolve("overlay_quad.png");
                if (Files.isRegularFile(ov)) {
                    Files.copy(ov, overlay.resolve(mode).resolve(name + ".png"), StandardCopyOption.REPLACE_EXISTING);
                    overlays++;
                }
            }
            if (composeCompare(compare.resolve(name + ".png"),
                    readJpg(corpusOut.resolve("ai_on/images").resolve(name).resolve("input.jpg")),
                    readPng(overlay.resolve("ai_on").resolve(name + ".png")),
                    readPng(overlay.resolve("ai_off").resolve(name + ".png")),
                    readJpg(corpusOut.resolve("ai_on/images").resolve(name).resolve("page.jpg")),
                    readJpg(corpusOut.resolve("ai_off/images").resolve(name).resolve("page.jpg")),
                    name)) strips++;
        }
        log("copied=" + copied + " overlays=" + overlays + " compareStrips=" + strips);

        writeBenchmarkReport(run, corpusOut, names);
        if (!problems.isEmpty()) {
            log("problems (" + problems.size() + "):");
            for (String p : problems) log("  - " + p);
        }
        log("DONE  benchmark=" + bench + "  overlay=" + overlay + "  overlay_compare=" + compare);
    }

    // ------------------------------------------------------------------ compare strip

    /** INPUT+quad(AI ON) | INPUT+quad(AI OFF) | AI-ON page | AI-OFF page. */
    private static boolean composeCompare(Path out, BufferedImage input,
                                          BufferedImage ovOn, BufferedImage ovOff,
                                          BufferedImage pageOn, BufferedImage pageOff,
                                          String name) {
        try {
            // Prefer the annotated overlays as the two "INPUT+quad" panels; fall back to the input.
            BufferedImage p1 = ovOn != null ? ovOn : input;
            BufferedImage p2 = ovOff != null ? ovOff : input;
            BufferedImage p3 = pageOn;
            BufferedImage p4 = pageOff;
            if (p1 == null && p2 == null && p3 == null && p4 == null) return false;

            int totalW = MARGIN * 2 + 4 * CELL_W + 3 * 10;
            int totalH = MARGIN * 2 + HEADER_H + CAP_H + CELL_H;
            BufferedImage canvas = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = canvas.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, totalW, totalH);
            g.setColor(new Color(0xEDEFF4));
            g.fillRect(0, 0, totalW, MARGIN + HEADER_H);
            g.setColor(new Color(0x2A2F3A));
            g.setFont(new Font("SansSerif", Font.BOLD, 21));
            g.drawString(name + "   [benchmark: real photo + GT flat scan]", MARGIN, MARGIN + 26);
            g.setFont(new Font("SansSerif", Font.PLAIN, 14));
            g.setColor(new Color(0x4A4F5A));
            g.drawString("Panels L->R: INPUT + detected quad (AI ON)  |  INPUT + detected quad (AI OFF)  |  "
                    + "AI-ON delivered page  |  AI-OFF delivered page", MARGIN, MARGIN + 50);

            int x = MARGIN;
            int y = MARGIN + HEADER_H;
            x = panel(g, x, y, p1, "INPUT + quad (AI ON)", C_ON);
            x = panel(g, x, y, p2, "INPUT + quad (AI OFF)", C_OFF);
            x = panel(g, x, y, p3, "AI-ON page (delivered)", C_ON);
            panel(g, x, y, p4, "AI-OFF page (delivered)", C_OFF);
            g.dispose();
            Files.createDirectories(out.getParent());
            ImageIO.write(canvas, "png", out.toFile());
            return true;
        } catch (Exception e) {
            log("  ! compare strip failed for " + name + ": " + e);
            return false;
        }
    }

    private static int panel(Graphics2D g, int x, int top, BufferedImage img, String caption, Color accent) {
        g.setColor(accent);
        g.fillRect(x, top, CELL_W, CAP_H);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 15));
        g.drawString(caption, x + 8, top + 30);
        g.setColor(new Color(0xFAFAFA));
        g.fillRect(x, top + CAP_H, CELL_W, CELL_H);
        g.setColor(new Color(0xB8BCC4));
        g.setStroke(new BasicStroke(1f));
        g.drawRect(x, top + CAP_H, CELL_W - 1, CELL_H - 1);
        if (img != null) {
            double s = Math.min(CELL_W / (double) img.getWidth(), CELL_H / (double) img.getHeight());
            int w = Math.max(1, (int) Math.round(img.getWidth() * s));
            int h = Math.max(1, (int) Math.round(img.getHeight() * s));
            g.drawImage(img, x + (CELL_W - w) / 2, top + CAP_H + (CELL_H - h) / 2, w, h, null);
        }
        return x + CELL_W + 10;
    }

    // ------------------------------------------------------------------ reports

    /**
     * Merges the per-mode benchmark CSV rows into one report with an ai_mode column, and writes a
     * tiny JSON wrapper that points at the two per-mode JSON reports.
     */
    private static void writeBenchmarkReport(Path run, Path corpusOut, List<String> names) throws IOException {
        StringBuilder csv = new StringBuilder();
        StringBuilder json = new StringBuilder("{\n  \"generated_by\": \"BenchmarkArtifacts\",\n"
                + "  \"group\": \"benchmark\",\n  \"images\": [\n");
        boolean firstJson = true;
        int rows = 0;
        boolean headerWritten = false;
        for (String mode : new String[]{"ai_on", "ai_off"}) {
            Path f = corpusOut.resolve(mode).resolve("benchmark_report_" + mode + ".csv");
            if (!Files.isRegularFile(f)) { log("  (no " + f.getFileName() + ")"); continue; }
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            if (lines.isEmpty()) continue;
            if (!headerWritten) { csv.append(lines.get(0)).append('\n'); headerWritten = true; }
            for (int i = 1; i < lines.size(); i++) {
                if (lines.get(i).isBlank()) continue;
                csv.append(lines.get(i)).append('\n');
                rows++;
                if (!firstJson) json.append(",\n");
                firstJson = false;
                json.append("    ").append(csvRowToJson(lines.get(0), lines.get(i)));
            }
        }
        Files.writeString(run.resolve("benchmark_report.csv"), csv.toString(), StandardCharsets.UTF_8);
        json.append("\n  ]\n}\n");
        Files.writeString(run.resolve("benchmark_report.json"), json.toString(), StandardCharsets.UTF_8);
        log("benchmark_report.csv rows=" + rows);
    }

    /** Best-effort CSV row -> JSON object (numeric fields emitted as numbers when they parse). */
    private static String csvRowToJson(String header, String row) {
        String[] hs = header.split(",", -1);
        String[] vs = row.split(",", -1);
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < hs.length && i < vs.length; i++) {
            if (i > 0) b.append(", ");
            b.append('"').append(hs[i].trim()).append("\": ");
            String v = vs[i].trim();
            if (v.matches("-?[0-9]+(\\.[0-9]+)?")) b.append(v);
            else if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) b.append(v.toLowerCase(Locale.ROOT));
            else b.append('"').append(v.replace("\"", "\\\"")).append('"');
        }
        return b.append('}').toString();
    }

    // ------------------------------------------------------------------ helpers

    /** Image names in the benchmark group, read from the lane's benchmark-only CSV. */
    private static List<String> benchmarkNames(Path corpusOut) {
        List<String> out = new ArrayList<>();
        Path csv = corpusOut.resolve("ai_on/benchmark_report_ai_on.csv");
        if (!Files.isRegularFile(csv)) return out;
        try {
            List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
            int col = -1;
            for (int i = 0; i < lines.size(); i++) {
                String[] parts = lines.get(i).split(",", -1);
                if (i == 0) {
                    for (int c = 0; c < parts.length; c++) if (parts[c].trim().equals("image")) col = c;
                    continue;
                }
                if (col >= 0 && col < parts.length && !parts[col].isBlank()) out.add(parts[col].trim());
            }
        } catch (Exception e) {
            log("  ! read " + csv.getFileName() + " failed: " + e);
        }
        out.sort(String::compareTo);
        return out;
    }

    private static List<String> listImages(Path dir) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) if (Files.isDirectory(p)) out.add(p.getFileName().toString());
        }
        out.sort(String::compareTo);
        return out;
    }

    private static BufferedImage readJpg(Path p) {
        try { return Files.isRegularFile(p) ? ImageIO.read(p.toFile()) : null; } catch (Exception e) { return null; }
    }

    private static BufferedImage readPng(Path p) {
        try { return Files.isRegularFile(p) ? ImageIO.read(p.toFile()) : null; } catch (Exception e) { return null; }
    }

    private static void log(String msg) {
        System.out.println("[" + LocalTime.now().format(HMS) + "] " + msg);
    }
}
