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
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.Graphics2D;

/**
 * DocScanAI SUBTASK H3a - host-side benchmark gallery refresher.
 *
 * Runs AFTER {@code CreateGallery} and:
 *  1. copies {@code <run>/overlay_compare/*.png} into {@code gallery/overlay_compare/} and makes
 *     browsable thumbnails of them under {@code gallery/thumbs_benchmark/};
 *  2. injects a {@code <!-- H3A_BENCHMARK --> ... } section at the top of {@code gallery/index.html}
 *     containing (a) the three-group metrics table for AI ON vs AI OFF — benchmark REAL / synthetic
 *     controlled / negatives, kept clearly separate — and (b) the benchmark before-state comparison
 *     strips, worst-first;
 *  3. rewrites {@code gallery/README.md} with accurate provenance for the run.
 *
 * The injection is idempotent: any previously injected block (delimited by the markers) is replaced,
 * so running it repeatedly is safe. JDK 21 only. Run:
 *   java tooling\BenchmarkGallery.java . h3a_20261016_1714
 */
public class BenchmarkGallery {

    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String BEGIN = "<!-- H3A_BENCHMARK:BEGIN -->";
    private static final String END = "<!-- H3A_BENCHMARK:END -->";

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        if (args.length < 2) { System.err.println("usage: BenchmarkGallery <root> <runId>"); System.exit(2); }
        String runId = args[1];
        Path run = root.resolve("tooling/out").resolve(runId);
        Path gallery = root.resolve("gallery");
        Path gCompare = gallery.resolve("overlay_compare");
        Path gThumb = gallery.resolve("thumbs_benchmark");
        Files.createDirectories(gCompare);
        Files.createDirectories(gThumb);

        // ---- 1) copy the before-state strips + make thumbnails ------------------------------
        int strips = 0;
        Path srcCompare = run.resolve("overlay_compare");
        if (Files.isDirectory(srcCompare)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(srcCompare, "*.png")) {
                for (Path p : ds) {
                    Files.copy(p, gCompare.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                    BufferedImage img = ImageIO.read(p.toFile());
                    if (img != null) {
                        double s = Math.min(1.0, 780.0 / img.getWidth());
                        int w = Math.max(1, (int) Math.round(img.getWidth() * s));
                        int h = Math.max(1, (int) Math.round(img.getHeight() * s));
                        BufferedImage t = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                        Graphics2D g = t.createGraphics();
                        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        g.drawImage(img, 0, 0, w, h, null);
                        g.dispose();
                        ImageIO.write(t, "png", gThumb.resolve(p.getFileName()).toFile());
                    }
                    strips++;
                }
            }
        }
        log("overlay_compare strips copied: " + strips);

        // ---- 2) read the reports -------------------------------------------------------------
        Path onCsv = run.resolve("corpus_out/ai_on/corpus_report_ai_on.csv");
        Path offCsv = run.resolve("corpus_out/ai_off/corpus_report_ai_off.csv");
        List<Map<String, String>> onRows = readCsv(onCsv);
        List<Map<String, String>> offRows = readCsv(offCsv);
        log("report rows: ai_on=" + onRows.size() + " ai_off=" + offRows.size());

        List<Map<String, String>> benchOn = filterGroup(onRows, "benchmark");
        benchOn.sort((a, b) -> Double.compare(num(b, "distortion_mad"), num(a, "distortion_mad")));

        String section = buildSection(runId, onRows, offRows, benchOn);
        Path index = gallery.resolve("index.html");
        String html = Files.isRegularFile(index) ? Files.readString(index, StandardCharsets.UTF_8) : "<html><body></body></html>";
        html = inject(html, section);
        Files.writeString(index, html, StandardCharsets.UTF_8);
        log("index.html refreshed: " + index);

        writeReadme(gallery, runId, run, onRows, offRows, strips, benchOn);
        log("README.md refreshed");
    }

    // ------------------------------------------------------------------ HTML

    private static String inject(String html, String section) {
        int b = html.indexOf(BEGIN);
        int e = html.indexOf(END);
        if (b >= 0 && e > b) {
            return html.substring(0, b) + section + html.substring(e + END.length());
        }
        int body = html.indexOf("<body");
        if (body >= 0) {
            int gt = html.indexOf('>', body);
            if (gt > 0) return html.substring(0, gt + 1) + "\n" + section + html.substring(gt + 1);
        }
        return section + html;
    }

    private static String buildSection(String runId, List<Map<String, String>> on, List<Map<String, String>> off,
                                       List<Map<String, String>> benchOn) {
        StringBuilder h = new StringBuilder();
        h.append(BEGIN).append('\n');
        h.append("<h1 id=\"h3a\">SUBTASK H3a — REAL benchmark (DocUNet) vs synthetic vs negatives</h1>\n");
        h.append("<p><b>Run:</b> <code>tooling/out/").append(runId).append("/</code> &nbsp; ")
                .append("<b>Benchmark report:</b> <code>tooling/out/").append(runId).append("/benchmark_report.csv</code> &nbsp; ")
                .append("<b>Overlays:</b> <code>tooling/out/").append(runId).append("/overlay/{ai_on,ai_off}/</code></p>\n");
        h.append("<p>The three groups are kept strictly separate: <b>benchmark</b> = real photographs of ")
                .append("document pages from the DocUNet Benchmark with a ground-truth flat <i>scan</i> of the same page; ")
                .append("<b>synthetic</b> = seeded renders with known corner ground truth; <b>negatives</b> = not-a-document ")
                .append("scene/texture images whose only requirement is “must not be warped”.</p>\n");

        h.append("<h2>Group metrics — AI ON vs AI OFF</h2>\n");
        h.append("<table border=\"1\" cellpadding=\"5\" cellspacing=\"0\">\n");
        h.append("<tr><th>group</th><th>mode</th><th>images</th><th>detected</th><th>detection rate</th>")
                .append("<th>full-frame fallback</th><th>mean PSNR vs GT scan</th><th>mean SSIM vs GT scan</th>")
                .append("<th>mean out WxH</th><th>in&rarr;out crispness (Laplacian var)</th>")
                .append("<th>in&rarr;out straightness (proj CV)</th><th>mean MAD</th>")
                .append("<th>irregular quads</th><th>distorted-suspect</th></tr>\n");
        for (String mode : new String[]{"ON", "OFF"}) {
            List<Map<String, String>> rows = mode.equals("ON") ? on : off;
            for (String g : new String[]{"benchmark", "synthetic", "negatives"}) {
                List<Map<String, String>> gr = filterGroup(rows, g);
                if (gr.isEmpty()) continue;
                h.append(row(mode, g, gr));
            }
        }
        h.append("</table>\n");

        h.append("<h2>Benchmark before-state strips (worst MAD first)</h2>\n");
        h.append("<p>Each strip: <b>INPUT + detected quad (AI ON)</b> | <b>INPUT + detected quad (AI OFF)</b> | ")
                .append("<b>AI-ON delivered page</b> | <b>AI-OFF delivered page</b>. Click for the full-size PNG.</p>\n");
        h.append("<table border=\"1\" cellpadding=\"4\" cellspacing=\"0\">\n<tr><th>name</th><th>detected</th>")
                .append("<th>warp path</th><th>out WxH</th><th>MAD</th><th>PSNR / SSIM vs GT scan</th>")
                .append("<th>strip</th></tr>\n");
        for (Map<String, String> r : benchOn) {
            String n = str(r, "image");
            String strip = "thumbs_benchmark/" + n + ".png";
            h.append("<tr><td><code>").append(n).append("</code></td>")
                    .append("<td>").append(str(r, "detected_found")).append("</td>")
                    .append("<td>").append(str(r, "warp_path")).append("</td>")
                    .append("<td>").append(str(r, "out_w")).append("x").append(str(r, "out_h")).append("</td>")
                    .append("<td>").append(fmt3(num(r, "distortion_mad"))).append("</td>")
                    .append("<td>").append(fmt3(num(r, "gt_psnr"))).append(" / ").append(fmt3(num(r, "gt_ssim"))).append("</td>")
                    .append("<td><a href=\"overlay_compare/").append(n).append(".png\">")
                    .append("<img src=\"").append(strip).append("\" width=\"380\" alt=\"").append(n).append("\"></a></td></tr>\n");
        }
        h.append("</table>\n");
        h.append(END).append('\n');
        return h.toString();
    }

    private static String row(String mode, String g, List<Map<String, String>> gr) {
        List<Map<String, String>> page = new ArrayList<>();
        for (Map<String, String> r : gr) if (!str(r, "kind").equals("negative")) page.add(r);
        int det = 0;
        for (Map<String, String> r : page) if (str(r, "detected_found").equals("true")) det++;
        double rate = page.isEmpty() ? 0.0 : 100.0 * det / page.size();
        return "<tr><td>" + g + "</td><td>" + mode + "</td><td>" + gr.size() + "</td><td>" + det + "/" + page.size()
                + "</td><td>" + fmt1(rate) + " %</td><td>" + count(gr, "full_frame_fallback") + "</td>"
                + "<td>" + fmt3(avg(gr, "gt_psnr")) + "</td><td>" + fmt3(avg(gr, "gt_ssim")) + "</td>"
                + "<td>" + fmt1(avg(gr, "out_w")) + "x" + fmt1(avg(gr, "out_h")) + "</td>"
                + "<td>" + fmt1(avg(gr, "in_sharpness")) + " &rarr; " + fmt1(avg(gr, "out_sharpness")) + "</td>"
                + "<td>" + fmt1(avg(gr, "in_straightness")) + " &rarr; " + fmt1(avg(gr, "out_straightness")) + "</td>"
                + "<td>" + fmt3(avg(gr, "distortion_mad")) + "</td>"
                + "<td>" + count(gr, "quad_order_ok", true) + "</td>"
                + "<td>" + count(gr, "distorted_suspect") + "</td></tr>\n";
    }

    private static String count(List<Map<String, String>> rs, String col) { return count(rs, col, false); }

    /** Counts rows where col == "true" (invert=true counts col == "false"). */
    private static String count(List<Map<String, String>> rs, String col, boolean invert) {
        int n = 0;
        for (Map<String, String> r : rs) {
            String v = str(r, col);
            if (invert ? v.equals("false") : v.equals("true")) n++;
        }
        return String.valueOf(n);
    }

    // ------------------------------------------------------------------ README

    private static void writeReadme(Path gallery, String runId, Path run,
                                    List<Map<String, String>> on, List<Map<String, String>> off,
                                    int strips, List<Map<String, String>> benchOn) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# DocScanAI corpus gallery (SUBTASK H2 + H3a)\n\n");
        sb.append("A human-browsable PNG gallery of the corpus **inputs**, the pipeline **outputs** and — added by ")
                .append("subtask H3a — the **detected-quad overlays** on the pre-warp input. Open [`index.html`](index.html).\n\n");
        sb.append("## Source run\n\n")
                .append("- Run folder: `tooling/out/").append(runId).append("/`\n")
                .append("- Corpus output: `tooling/out/").append(runId).append("/corpus_out/`\n")
                .append("- Benchmark artifacts: `tooling/out/").append(runId).append("/benchmark/{ai_on,ai_off}/<name>/`\n")
                .append("- Quad overlays: `tooling/out/").append(runId).append("/overlay/{ai_on,ai_off}/<name>.png`\n")
                .append("- Before-state strips: `tooling/out/").append(runId).append("/overlay_compare/<name>.png`\n")
                .append("- Machine-readable: `corpus_report.json`, `benchmark_report.csv`, `benchmark_report.json`\n\n");
        sb.append("## How it was produced\n\n")
                .append("Host-side JDK 21 tools only (no new dependencies):\n\n```\n")
                .append("set \"JAVA_HOME=C:\\Program Files\\Java\\jdk-21\"\n")
                .append("\"%JAVA_HOME%\\bin\\java.exe\" tooling\\BenchmarkArtifacts.java . ").append(runId).append("\n")
                .append("\"%JAVA_HOME%\\bin\\java.exe\" tooling\\CreateGallery.java . ").append(runId).append("\n")
                .append("\"%JAVA_HOME%\\bin\\java.exe\" tooling\\BenchmarkGallery.java . ").append(runId).append("\n```\n\n");
        sb.append("## Groups (kept separate)\n\n")
                .append("| group | what it is | ground truth |\n|---|---|---|\n")
                .append("| **benchmark** | REAL photographs of document pages, DocUNet Benchmark (Ma et al., CVPR 2018) | flat **scan** of the same page (PSNR/SSIM); no corner annotation shipped |\n")
                .append("| **synthetic** | seeded in-process renders | corner coordinates (+ the bundled flat page) |\n")
                .append("| **negatives** | real scene / texture photos, no document | none — the only requirement is “must not be warped” |\n\n");
        sb.append("## What is in this folder\n\n")
                .append("| Path | Contents |\n|---|---|\n")
                .append("| `inputs/<name>.png` | every corpus INPUT the lane used (bounded to ≤ 900 px long edge) |\n")
                .append("| `raw/<name>.png` | the raw pre-warp capture the pipeline stored |\n")
                .append("| `outputs_ai_on/<name>.png`, `outputs_ai_off/<name>.png` | the delivered page per AI mode |\n")
                .append("| `overlay_compare/<name>.png` | **H3a** — INPUT+quad(AI ON) \\| INPUT+quad(AI OFF) \\| AI-ON page \\| AI-OFF page |\n")
                .append("| `thumbs_benchmark/<name>.png` | scaled previews of the before-state strips |\n")
                .append("| `side_by_side/<name>.png` | INPUT \\| RAW \\| AI-ON page \\| AI-OFF page strips |\n")
                .append("| `thumbs/*_<name>.png` | index thumbnails |\n")
                .append("| `index.html` | browsable, benchmark section first |\n\n");
        sb.append("## Summary numbers\n\n")
                .append("- Benchmark before-state strips composed: **").append(strips).append("**\n")
                .append("- Benchmark rows (AI ON): **").append(benchOn.size()).append("**\n")
                .append("- Report rows: AI ON **").append(on.size()).append("**, AI OFF **").append(off.size()).append("**\n\n")
                .append("Group-level numbers (detection rate, PSNR/SSIM vs the GT scan, delivered output dimensions, ")
                .append("crispness/straightness, MAD, irregular quads, distorted-suspect) are tabulated at the top of ")
                .append("[`index.html`](index.html).\n\n");
        sb.append("## Note on the overlays\n\n")
                .append("Overlays are **diagnostic only** — they draw the 4 detected corners (numbered in pipeline order, ")
                .append("connected, coordinates printed) on a copy of the pre-warp input, plus a header with the name, mode, ")
                .append("detection verdict, full-frame-fallback flag, quad area fraction, corner error versus GT (when ")
                .append("available) and the delivered output size. They never alter a stored page, and no user-visible UI is added.\n");
        Files.writeString(gallery.resolve("README.md"), sb.toString(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ helpers

    private static List<Map<String, String>> readCsv(Path p) {
        List<Map<String, String>> out = new ArrayList<>();
        if (!Files.isRegularFile(p)) return out;
        try {
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            if (lines.isEmpty()) return out;
            String[] hs = lines.get(0).split(",", -1);
            for (int i = 1; i < lines.size(); i++) {
                if (lines.get(i).isBlank()) continue;
                String[] vs = lines.get(i).split(",", -1);
                Map<String, String> m = new LinkedHashMap<>();
                for (int c = 0; c < hs.length && c < vs.length; c++) m.put(hs[c].trim(), vs[c].trim());
                out.add(m);
            }
        } catch (Exception e) {
            log("  ! read " + p.getFileName() + " failed: " + e);
        }
        return out;
    }

    private static List<Map<String, String>> filterGroup(List<Map<String, String>> rows, String g) {
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, String> r : rows) if (g.equals(str(r, "group"))) out.add(r);
        return out;
    }

    private static String str(Map<String, String> r, String k) { return r.getOrDefault(k, ""); }

    private static double num(Map<String, String> r, String k) {
        try { return Double.parseDouble(str(r, k)); } catch (Exception e) { return -1.0; }
    }

    /** Mean over rows with a known (>= 0) value. */
    private static double avg(List<Map<String, String>> rs, String k) {
        double sum = 0; int n = 0;
        for (Map<String, String> r : rs) { double v = num(r, k); if (v >= 0) { sum += v; n++; } }
        return n == 0 ? -1.0 : sum / n;
    }

    private static String fmt1(double d) { return d < 0 ? "n/a" : String.format(Locale.US, "%.1f", d); }
    private static String fmt3(double d) { return d < 0 ? "n/a" : String.format(Locale.US, "%.3f", d); }

    private static void log(String m) { System.out.println("[" + LocalTime.now().format(HMS) + "] " + m); }
}
