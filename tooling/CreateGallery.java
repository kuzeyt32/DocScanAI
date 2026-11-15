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
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * DocScanAI SUBTASK H2 - host-side PNG gallery builder.
 *
 * Reads the inputs/outputs already captured on disk by the last corpus run and composes a
 * human-browsable PNG gallery (no emulator, no adb, no new dependencies). Uses ONLY the pinned
 * JDK 21 (javax.imageio + java.awt Java2D). Mirrors the single-file style of
 * tooling/corpus/FetchCorpus.java.
 *
 * What it emits under <projectRoot>/gallery :
 *   inputs/<name>.png            every corpus INPUT converted to PNG
 *   raw/<name>.png               the raw pre-warp capture (reference)
 *   outputs_ai_on/<name>.png     the AI-ON processed page
 *   outputs_ai_off/<name>.png    the AI-OFF processed page
 *   thumbs/{in,on,off}_<name>.png small thumbnails for index.html (max width 260)
 *   side_by_side/<name>.png      INPUT | RAW | AI-ON page | AI-OFF page comparison strip
 *   index.html                   browsable grid grouped by category (worst offenders first)
 *   README.md                    provenance, layout, summary numbers
 *
 * Run with (from the DocScanAI project root):
 *   "C:\Program Files\Java\jdk-21\bin\java.exe" tooling\CreateGallery.java .
 *
 * Optional 2nd arg overrides the corpus run folder id (default corpus_Cum16102026_19363025).
 * Missing per-image files are logged and skipped, never fatal.
 */
public class CreateGallery {

    private static final String DEFAULT_RUN_ID = "corpus_Cum16102026_19363025";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    // ---- side-by-side strip layout ----
    private static final int CELL_W = 720;   // per-panel cell width
    private static final int IMG_H = 900;    // bounded panel image height (task: ~900 px)
    private static final int CAP_H = 158;    // caption band above each panel
    private static final int HEADER_H = 92;  // two-line header band
    private static final int MARGIN = 20;
    private static final int SEP = 8;

    private static final int THUMB_W = 260;  // index.html thumbnail max width

    // Browsing bound for the standalone converted PNGs (inputs/raw/outputs) so the whole gallery
    // stays modest - the full-resolution originals remain untouched under tooling/out/**.
    private static final int STANDALONE_MAX = 900;

    // palette
    private static final Color C_INPUT = new Color(0x1B3A6B);
    private static final Color C_RAW = new Color(0x555555);
    private static final Color C_ON = new Color(0xA02020);
    private static final Color C_OFF = new Color(0x1B6B33);
    private static final Color C_HEAD_BG = new Color(0xEDEFF4);
    private static final Color C_IMG_BG = new Color(0xFAFAFA);
    private static final Color C_SEP = new Color(0xB8BCC4);
    private static final Color C_SUSPECT = new Color(0xC00000);
    private static final Color C_OK = new Color(0x1B6B33);

    // per-image parsed verdict row
    private static final class Row {
        String name = "";
        String category = "";
        String warpPath = "";
        String note = "";
        boolean hasGt;
        boolean detected;
        boolean nearFull;
        boolean suspect;
        boolean aspectPlausible = true;
        double mad = -1;
        int outW, outH;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");

        Path projectRoot = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        String runId = args.length > 1 ? args[1] : DEFAULT_RUN_ID;

        Path corpusOut = projectRoot.resolve("tooling/out").resolve(runId).resolve("corpus_out");
        Path onDir = corpusOut.resolve("ai_on").resolve("images");
        Path offDir = corpusOut.resolve("ai_off").resolve("images");
        Path onCsv = corpusOut.resolve("ai_on").resolve("corpus_report_ai_on.csv");
        Path offCsv = corpusOut.resolve("ai_off").resolve("corpus_report_ai_off.csv");
        Path jsonReport = corpusOut.resolve("corpus_report.json");

        Path gallery = projectRoot.resolve("gallery");
        Path pIn = gallery.resolve("inputs");
        Path pRaw = gallery.resolve("raw");
        Path pOn = gallery.resolve("outputs_ai_on");
        Path pOff = gallery.resolve("outputs_ai_off");
        Path pThumb = gallery.resolve("thumbs");
        Path pStrip = gallery.resolve("side_by_side");

        for (Path d : Arrays.asList(gallery, pIn, pRaw, pOn, pOff, pThumb, pStrip)) {
            Files.createDirectories(d);
        }

        log("gallery root : " + gallery);
        log("corpus out   : " + corpusOut);

        if (!Files.isDirectory(onDir) || !Files.isDirectory(offDir)) {
            log("FATAL: corpus images dir not found under " + corpusOut);
            log("Refusing to re-run the harness; stop.");
            System.exit(2);
        }

        Map<String, Row> onRows = readCsv(onCsv);
        Map<String, Row> offRows = readCsv(offCsv);
        log("verdict rows : ai_on=" + onRows.size() + "  ai_off=" + offRows.size());

        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(onDir)) {
            for (Path d : ds) {
                if (Files.isDirectory(d)) names.add(d.getFileName().toString());
            }
        }
        names.sort(String::compareTo);
        log("images found : " + names.size());

        List<String> missing = new ArrayList<>();   // "name: which files missing / decode failed"
        List<String> built = new ArrayList<>();

        int i = 0;
        int done = 0;
        for (String name : names) {
            i++;
            try {
                boolean ok = buildOne(name, onDir, offDir, onRows, offRows, pIn, pRaw, pOn, pOff, pThumb, pStrip, missing);
                if (ok) { built.add(name); done++; }
            } catch (Exception ex) {
                missing.add(name + ": unexpected error: " + ex);
                log("  ! " + name + " failed: " + ex);
            }
            if (i % 10 == 0) {
                log("progress: composed " + i + "/" + names.size() + " (ok=" + done + ")");
            }
        }
        log("progress: composed " + i + "/" + names.size() + " (ok=" + done + ")");

        // summary numbers
        Summary sum = summarize(onRows, offRows, names.size());
        List<Row> top = topWorst(onRows, 5);

        log("summary: notDetected=" + sum.notDetected + "/" + sum.total
                + "  suspectOn=" + sum.suspectOn + "  suspectOff=" + sum.suspectOff);

        writeIndexHtml(gallery, names, onRows, offRows, sum, top);
        writeReadme(gallery, runId, corpusOut, onCsv, offCsv, jsonReport, sum, top, missing, names.size());

        long totalBytes = dirSize(gallery);
        log("built " + built.size() + " strips; skipped " + missing.size() + " item(s)");
        log("gallery total size: " + human(totalBytes));
        log("OPEN: double-click " + gallery.resolve("index.html"));
    }

    // ------------------------------------------------------------------ per image

    private static boolean buildOne(String name, Path onDir, Path offDir,
                                    Map<String, Row> onRows, Map<String, Row> offRows,
                                    Path pIn, Path pRaw, Path pOn, Path pOff, Path pThumb, Path pStrip,
                                    List<String> missing) throws IOException {
        Path onBase = onDir.resolve(name);
        Path offBase = offDir.resolve(name);

        BufferedImage input = readJpg(onBase.resolve("input.jpg"));
        BufferedImage raw = readJpg(onBase.resolve("raw.jpg"));
        BufferedImage onPage = readJpg(onBase.resolve("page.jpg"));
        BufferedImage offPage = readJpg(offBase.resolve("page.jpg"));

        if (input == null) missing.add(name + ": input.jpg missing/undecodable");
        if (raw == null) missing.add(name + ": raw.jpg missing/undecodable");
        if (onPage == null) missing.add(name + ": ai_on page.jpg missing/undecodable");
        if (offPage == null) missing.add(name + ": ai_off page.jpg missing/undecodable");

        // 1) inputs / raw / outputs as PNGs (bounded to STANDALONE_MAX for browsing; originals stay in tooling/out)
        if (input != null) writePng(scaleToFit(input, STANDALONE_MAX, STANDALONE_MAX), pIn.resolve(name + ".png"));
        if (raw != null) writePng(scaleToFit(raw, STANDALONE_MAX, STANDALONE_MAX), pRaw.resolve(name + ".png"));
        if (onPage != null) writePng(scaleToFit(onPage, STANDALONE_MAX, STANDALONE_MAX), pOn.resolve(name + ".png"));
        if (offPage != null) writePng(scaleToFit(offPage, STANDALONE_MAX, STANDALONE_MAX), pOff.resolve(name + ".png"));

        // 2) thumbnails for index.html
        if (input != null) writePng(scaleToFit(input, THUMB_W, 10000), pThumb.resolve("in_" + name + ".png"));
        if (onPage != null) writePng(scaleToFit(onPage, THUMB_W, 10000), pThumb.resolve("on_" + name + ".png"));
        if (offPage != null) writePng(scaleToFit(offPage, THUMB_W, 10000), pThumb.resolve("off_" + name + ".png"));

        // 3) the key deliverable: the comparison strip
        Row on = onRows.get(name);
        Row off = offRows.get(name);
        composeStrip(pStrip.resolve(name + ".png"), name, on, off, input, raw, onPage, offPage);
        return true;
    }

    private static void composeStrip(Path out, String name, Row on, Row off,
                                     BufferedImage input, BufferedImage raw,
                                     BufferedImage onPage, BufferedImage offPage) throws IOException {
        String category = on != null ? on.category : (off != null ? off.category : "unknown");
        boolean suspectOn = on != null && on.suspect;
        boolean suspectOff = off != null && off.suspect;

        int totalW = MARGIN * 2 + 4 * CELL_W + 3 * SEP;
        int totalH = MARGIN * 2 + HEADER_H + CAP_H + IMG_H;

        BufferedImage canvas = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, totalW, totalH);

        // ---- header band ----
        g.setColor(C_HEAD_BG);
        g.fillRect(0, 0, totalW, MARGIN + HEADER_H);
        g.setColor(new Color(0x2A2F3A));
        g.setFont(new Font("SansSerif", Font.BOLD, 22));
        g.drawString(clip(g, name + "   [" + category + "]", totalW - 2 * MARGIN), MARGIN, MARGIN + 26);

        g.setFont(new Font("SansSerif", Font.PLAIN, 15));
        g.setColor(new Color(0x3A3F4A));
        String line2 = verdictLine("AI-ON", on) + "     ||     " + verdictLine("AI-OFF", off);
        g.drawString(clip(g, line2, totalW - 2 * MARGIN), MARGIN, MARGIN + 50);

        g.setColor(new Color(0x6A6F7A));
        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        g.drawString("Panels L->R: INPUT | RAW (pre-warp) | AI-ON page | AI-OFF page.   "
                + "Failure mode: on a detection miss the pipeline still warps; with AI ON the learned UVDoc grid is applied to the whole frame.",
                MARGIN, MARGIN + 72);

        int capTop = MARGIN + HEADER_H;

        // ---- four panels ----
        String[] labels = {"1) INPUT  — corpus photo fed to the lane", "2) RAW  — pre-warp capture stored by pipeline",
                "3) AI-ON  — processed page (neural UVDoc path)", "4) AI-OFF  — processed page (classical/perspective path)"};
        BufferedImage[] imgs = {input, raw, onPage, offPage};
        Color[] colors = {C_INPUT, C_RAW, C_ON, C_OFF};
        Row[] rows = {null, null, on, off};
        String[] details = {
                "category: " + category,
                "reference capture (independent of AI mode)",
                detailOnOff(on),
                detailOnOff(off)
        };

        for (int p = 0; p < 4; p++) {
            int x = MARGIN + p * (CELL_W + SEP);

            // caption band
            g.setColor(new Color(0xF5F6F8));
            g.fillRect(x, capTop, CELL_W, CAP_H);
            g.setColor(colors[p]);
            g.fillRect(x, capTop, CELL_W, 4);

            g.setFont(new Font("SansSerif", Font.BOLD, 16));
            g.setColor(colors[p]);
            g.drawString(clip(g, labels[p], CELL_W - 16), x + 8, capTop + 26);

            g.setFont(new Font("SansSerif", Font.PLAIN, 14));
            boolean sus = rows[p] != null && rows[p].suspect;
            g.setColor(sus ? C_SUSPECT : new Color(0x333333));
            List<String> lines = wrap(g, details[p], CELL_W - 16);
            int y = capTop + 50;
            for (int li = 0; li < lines.size() && li < 4; li++) {
                g.drawString(lines.get(li), x + 8, y);
                y += 20;
            }

            // image area
            int imgTop = capTop + CAP_H;
            g.setColor(C_IMG_BG);
            g.fillRect(x, imgTop, CELL_W, IMG_H);
            BufferedImage img = imgs[p];
            if (img == null) {
                g.setColor(C_SUSPECT);
                g.setFont(new Font("SansSerif", Font.BOLD, 20));
                g.drawString("MISSING", x + CELL_W / 2 - 55, imgTop + IMG_H / 2);
            } else {
                BufferedImage scaled = scaleToFit(img, CELL_W - 16, IMG_H - 16);
                int dx = x + (CELL_W - scaled.getWidth()) / 2;
                int dy = imgTop + (IMG_H - scaled.getHeight()) / 2;
                g.drawImage(scaled, dx, dy, null);
                g.setColor(new Color(0xDDDDDD));
                g.drawRect(dx - 1, dy - 1, scaled.getWidth() + 1, scaled.getHeight() + 1);
            }

            // thin separator
            if (p < 3) {
                g.setColor(C_SEP);
                g.setStroke(new BasicStroke(1f));
                g.drawLine(x + CELL_W + SEP / 2, capTop, x + CELL_W + SEP / 2, capTop + CAP_H + IMG_H);
            }
        }

        g.dispose();
        writePng(canvas, out);
    }

    private static String detailOnOff(Row r) {
        if (r == null) return "no report row for this mode";
        return "detected=" + (r.detected ? "YES" : "NO")
                + "   found=" + (r.detected ? "yes" : "no")
                + "   warp=" + r.warpPath
                + "   MAD=" + fmt(r.mad)
                + "   near-full-frame=" + (r.nearFull ? "yes" : "no")
                + "   DISTORTED-SUSPECT=" + (r.suspect ? "YES" : "no");
    }

    private static String verdictLine(String mode, Row r) {
        if (r == null) return mode + ": (no row)";
        return mode + ": detected=" + (r.detected ? "YES" : "NO")
                + " warp=" + r.warpPath
                + " MAD=" + fmt(r.mad)
                + " nearFull=" + (r.nearFull ? "Y" : "N")
                + " SUSPECT=" + (r.suspect ? "YES" : "no");
    }

    // ------------------------------------------------------------------ index.html

    private static void writeIndexHtml(Path gallery, List<String> names,
                                       Map<String, Row> onRows, Map<String, Row> offRows,
                                       Summary sum, List<Row> top) throws IOException {
        // group order
        LinkedHashMap<String, List<String>> groups = new LinkedHashMap<>();
        for (String g : new String[]{"rotation", "perspective", "out-of-frame", "rectified",
                "lighting", "degradation", "NEGATIVE no-document", "real photos", "other"}) {
            groups.put(g, new ArrayList<>());
        }
        for (String name : names) {
            Row r = onRows.get(name);
            if (r == null) r = offRows.get(name);
            String g = groupOf(r != null ? r.category : "unknown");
            groups.computeIfAbsent(g, k -> new ArrayList<>()).add(name);
        }

        Comparator<String> worst = (a, b) -> {
            Row ra = onRows.get(a), rb = onRows.get(b);
            boolean sa = ra != null && ra.suspect, sb = rb != null && rb.suspect;
            if (sa != sb) return sa ? -1 : 1;
            double ma = ra != null ? ra.mad : -1, mb = rb != null ? rb.mad : -1;
            if (ma != mb) return Double.compare(mb, ma);
            return a.compareTo(b);
        };

        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n");
        sb.append("<title>DocScanAI corpus gallery</title>\n<style>\n");
        sb.append("body{font-family:Segoe UI,Arial,sans-serif;background:#1b1e24;color:#e8eaee;margin:0;padding:22px;}\n");
        sb.append("h1{font-size:22px;margin:0 0 6px 0;} h2{font-size:18px;margin:26px 0 10px 0;border-bottom:1px solid #3a3f4a;padding-bottom:6px;}\n");
        sb.append(".note{background:#232733;border:1px solid #3a3f4a;border-radius:8px;padding:12px 14px;line-height:1.5;font-size:13.5px;max-width:1400px;}\n");
        sb.append(".summary{font-size:15px;font-weight:600;color:#ffd479;margin:10px 0 0 0;}\n");
        sb.append("table{border-collapse:collapse;width:100%;max-width:1600px;}\n");
        sb.append("tr.row{border-bottom:1px solid #2b2f38;vertical-align:top;}\n");
        sb.append("td{padding:8px 10px;} td.thumb{width:270px;}\n");
        sb.append("img.t{width:260px;border:1px solid #3a3f4a;border-radius:4px;background:#fff;display:block;}\n");
        sb.append(".cap{font-size:12px;color:#9aa2b1;margin-top:3px;}\n");
        sb.append(".name{font-family:Consolas,monospace;font-size:13px;color:#cfe3ff;word-break:break-all;}\n");
        sb.append(".cat{font-size:12px;color:#9aa2b1;}\n");
        sb.append(".verdict{font-size:13px;line-height:1.45;}\n");
        sb.append(".sus{color:#ff6b6b;font-weight:700;} .ok{color:#7ee08a;}\n");
        sb.append(".worst{border-left:4px solid #ff6b6b;padding-left:10px;}\n");
        sb.append("a{color:#8fd0ff;} a.open{font-size:12px;}\n");
        sb.append(".badge{display:inline-block;font-size:11px;padding:1px 6px;border-radius:10px;background:#3a3f4a;color:#dfe5ee;margin-right:6px;}\n");
        sb.append("</style></head><body>\n");

        sb.append("<h1>DocScanAI &mdash; corpus INPUT vs OUTPUT gallery</h1>\n");
        sb.append("<div class=\"note\">");
        sb.append("<b>What to look for:</b> the third panel (AI-ON) is where the documented failure mode shows. ");
        sb.append("On a <b>detection miss</b> the pipeline still warps unconditionally, and with AI ON the ");
        sb.append("<b>learned UVDoc grid is applied to the whole frame</b> &rarr; visible pinching/curvature on negatives, ");
        sb.append("textured and out-of-frame shots. The fourth panel (AI-OFF) uses the classical/perspective path and stays clean. ");
        sb.append("Panels are <b>INPUT | RAW | AI-ON page | AI-OFF page</b>; click any strip to open the full-size comparison.");
        sb.append("<div class=\"summary\">Summary: ").append(sum.notDetected).append("/").append(sum.total)
                .append(" not detected; ").append(sum.suspectOn).append(" distorted-suspect with AI ON; ")
                .append(sum.suspectOff).append(" with AI OFF.</div>");
        sb.append("</div>\n");

        // top worst
        sb.append("<h2>Worst offenders (AI ON, distorted-suspect, highest MAD first)</h2>\n");
        sb.append("<table>\n");
        for (Row r : top) {
            sb.append(rowHtml(r.name, onRows.get(r.name), offRows.get(r.name), true));
        }
        sb.append("</table>\n");

        // grouped
        for (Map.Entry<String, List<String>> e : groups.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            List<String> list = new ArrayList<>(e.getValue());
            list.sort(worst);
            int susN = 0;
            for (String n : list) { Row r = onRows.get(n); if (r != null && r.suspect) susN++; }
            sb.append("<h2>").append(esc(e.getKey())).append(" <span class=\"cat\">(")
                    .append(list.size()).append(" images")
                    .append(susN > 0 ? ", " + susN + " distorted-suspect AI ON" : "")
                    .append(")</span></h2>\n<table>\n");
            for (String n : list) {
                sb.append(rowHtml(n, onRows.get(n), offRows.get(n), false));
            }
            sb.append("</table>\n");
        }

        sb.append("</body></html>\n");
        Files.writeString(gallery.resolve("index.html"), sb.toString(), StandardCharsets.UTF_8);
    }

    private static String rowHtml(String name, Row on, Row off, boolean worst) {
        String cat = on != null ? on.category : (off != null ? off.category : "unknown");
        StringBuilder b = new StringBuilder();
        b.append("<tr class=\"row").append(worst ? " worst" : "").append("\">\n");
        b.append("<td class=\"thumb\"><a href=\"side_by_side/").append(enc(name)).append(".png\">");
        b.append("<img class=\"t\" src=\"thumbs/in_").append(enc(name)).append(".png\" alt=\"input\"></a>");
        b.append("<div class=\"cap\">INPUT</div></td>\n");
        b.append("<td class=\"thumb\"><a href=\"side_by_side/").append(enc(name)).append(".png\">");
        b.append("<img class=\"t\" src=\"thumbs/on_").append(enc(name)).append(".png\" alt=\"ai_on\"></a>");
        b.append("<div class=\"cap\">AI-ON page</div></td>\n");
        b.append("<td class=\"thumb\"><a href=\"side_by_side/").append(enc(name)).append(".png\">");
        b.append("<img class=\"t\" src=\"thumbs/off_").append(enc(name)).append(".png\" alt=\"ai_off\"></a>");
        b.append("<div class=\"cap\">AI-OFF page</div></td>\n");
        b.append("<td><div class=\"name\">").append(esc(name)).append("</div>");
        b.append("<div class=\"cat\">category: ").append(esc(cat)).append("</div>");
        b.append("<div class=\"verdict\">").append(verdictHtml("AI-ON", on)).append("<br>")
                .append(verdictHtml("AI-OFF", off)).append("</div>");
        b.append("<a class=\"open\" href=\"side_by_side/").append(enc(name)).append(".png\">open full strip &rarr;</a></td>\n");
        b.append("</tr>\n");
        return b.toString();
    }

    private static String verdictHtml(String mode, Row r) {
        if (r == null) return "<span class=\"badge\">" + mode + "</span>(no row)";
        StringBuilder b = new StringBuilder();
        b.append("<span class=\"badge\">").append(mode).append("</span>");
        b.append("detected=<b>").append(r.detected ? "YES" : "NO").append("</b>");
        b.append(", warp=").append(esc(r.warpPath));
        b.append(", MAD=").append(fmt(r.mad));
        b.append(", near-full-frame=").append(r.nearFull ? "yes" : "no");
        b.append(", <span class=\"").append(r.suspect ? "sus\">DISTORTED-SUSPECT=YES" : "ok\">DISTORTED-SUSPECT=no").append("</span>");
        return b.toString();
    }

    // ------------------------------------------------------------------ README.md

    private static void writeReadme(Path gallery, String runId, Path corpusOut, Path onCsv, Path offCsv,
                                    Path jsonReport, Summary sum, List<Row> top,
                                    List<String> missing, int total) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append("# DocScanAI corpus gallery (SUBTASK H2)\n\n");
        b.append("A human-browsable PNG gallery of the corpus **inputs** and the pipeline **outputs**, so the ");
        b.append("debug lane's behaviour can be inspected with the naked eye. Open [`index.html`](index.html) in a browser.\n\n");

        b.append("## Source run\n\n");
        b.append("- Corpus run folder: `tooling/out/").append(runId).append("/`\n");
        b.append("- Corpus output: `tooling/out/").append(runId).append("/corpus_out/`\n");
        b.append("- Machine-readable reports:\n");
        b.append("  - `").append(rel(gallery, jsonReport)).append("`\n");
        b.append("  - `").append(rel(gallery, onCsv)).append("`\n");
        b.append("  - `").append(rel(gallery, offCsv)).append("`\n");
        b.append("- No emulator / adb was used; this gallery is pure host-side conversion of artifacts already on disk.\n\n");

        b.append("## How it was produced\n\n");
        b.append("Single-file JDK 21 tool (no new dependencies, `javax.imageio` + `java.awt` only), consistent with ");
        b.append("`tooling/corpus/FetchCorpus.java`. Run from the project root:\n\n");
        b.append("```\n");
        b.append("cd /d C:\\Users\\Kuzey\\Desktop\\DocScanAI\n");
        b.append("\"C:\\Program Files\\Java\\jdk-21\\bin\\java.exe\" tooling\\CreateGallery.java .\n");
        b.append("```\n\n");
        b.append("(optional 2nd arg overrides the run id; default `").append(DEFAULT_RUN_ID).append("`.)\n\n");

        b.append("## What is in this folder\n\n");
        b.append("| Path | Contents |\n|---|---|\n");
        b.append("| `inputs/<name>.png` | every corpus INPUT the lane used, converted to PNG (bounded to \u2264 900 px long edge for browsing) |\n");
        b.append("| `raw/<name>.png` | the raw pre-warp capture the pipeline stored (reference; bounded to \u2264 900 px) |\n");
        b.append("| `outputs_ai_on/<name>.png` | the processed page with AI ON (bounded to \u2264 900 px) |\n");
        b.append("| `outputs_ai_off/<name>.png` | the processed page with AI OFF (bounded to \u2264 900 px) |\n");
        b.append("| `side_by_side/<name>.png` | **key deliverable** — one strip per image: INPUT | RAW | AI-ON page | AI-OFF page, with captions and the verdict from `corpus_report*.csv` |\n");
        b.append("| `thumbs/*_<name>.png` | small thumbnails used by `index.html` |\n");
        b.append("| `index.html` | browsable grid grouped by category, worst offenders first |\n\n");

        b.append("Naming: synthetic variants are `syn_*` (generated in-test); real photos are `real_*` and correspond to the ");
        b.append("12 photos under `app/src/androidTest/assets/corpus/real/` (the lane's captured `input.jpg` for each is what is shown).\n\n");

        b.append("## Summary numbers (from the machine-readable reports)\n\n");
        b.append("- Total images: **").append(total).append("**\n");
        b.append("- Not detected: **").append(sum.notDetected).append("/").append(total).append("**\n");
        b.append("- DISTORTED-SUSPECT with AI ON: **").append(sum.suspectOn).append("**\n");
        b.append("- DISTORTED-SUSPECT with AI OFF: **").append(sum.suspectOff).append("**\n\n");
        b.append("> ").append(sum.notDetected).append("/").append(total).append(" not detected; ")
                .append(sum.suspectOn).append(" distorted-suspect with AI ON; ")
                .append(sum.suspectOff).append(" with AI OFF.\n\n");

        b.append("## Top worst distorted-suspect cases (AI ON, by MAD)\n\n");
        b.append("| # | name | category | warp path | MAD | side-by-side |\n|---|---|---|---|---|---|\n");
        int k = 1;
        for (Row r : top) {
            b.append("| ").append(k++).append(" | `").append(r.name).append("` | ").append(r.category)
                    .append(" | ").append(r.warpPath).append(" | ").append(fmt(r.mad))
                    .append(" | `side_by_side/").append(r.name).append(".png` |\n");
        }
        b.append("\n");

        b.append("## Images skipped or with missing artifacts\n\n");
        if (missing.isEmpty()) {
            b.append("None — all ").append(total).append(" images converted cleanly.\n\n");
        } else {
            b.append("The following items were logged and skipped (the job continued, it did not fail):\n\n");
            b.append("```\n");
            for (String m : missing) b.append(m).append("\n");
            b.append("```\n\n");
        }

        b.append("## What this gallery demonstrates\n\n");
        b.append("Detection miss &rarr; the pipeline still warps unconditionally &rarr; with AI ON the learned UVDoc grid ");
        b.append("is applied to the whole frame (visible distortion on negatives/textured/out-of-frame shots); the same ");
        b.append("images stay clean with AI OFF.\n");

        Files.writeString(gallery.resolve("README.md"), b.toString(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ helpers

    private static final class Summary {
        int total, notDetected, suspectOn, suspectOff;
    }

    private static Summary summarize(Map<String, Row> onRows, Map<String, Row> offRows, int total) {
        Summary s = new Summary();
        s.total = total;
        for (Row r : onRows.values()) {
            if (!r.detected) s.notDetected++;
            if (r.suspect) s.suspectOn++;
        }
        for (Row r : offRows.values()) {
            if (r.suspect) s.suspectOff++;
        }
        return s;
    }

    private static List<Row> topWorst(Map<String, Row> onRows, int n) {
        List<Row> all = new ArrayList<>();
        for (Row r : onRows.values()) if (r.suspect) all.add(r);
        all.sort((a, b) -> Double.compare(b.mad, a.mad));
        return all.size() > n ? new ArrayList<>(all.subList(0, n)) : all;
    }

    private static String groupOf(String category) {
        if (category.startsWith("rotation")) return "rotation";
        if (category.startsWith("perspective")) return "perspective";
        if (category.equals("partial_out_of_frame")) return "out-of-frame";
        if (category.equals("rectified_full")) return "rectified";
        if (category.startsWith("lighting")) return "lighting";
        if (category.startsWith("degradation")) return "degradation";
        if (category.startsWith("negative")) return "NEGATIVE no-document";
        if (category.equals("real_photo")) return "real photos";
        return "other";
    }

    private static Map<String, Row> readCsv(Path csv) throws IOException {
        Map<String, Row> map = new LinkedHashMap<>();
        if (!Files.isRegularFile(csv)) {
            log("WARN: report CSV not found: " + csv);
            return map;
        }
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        if (lines.isEmpty()) return map;
        String[] hdr = lines.get(0).split(",", -1);
        Map<String, Integer> idx = new LinkedHashMap<>();
        for (int i = 0; i < hdr.length; i++) idx.put(hdr[i].trim(), i);
        for (int li = 1; li < lines.size(); li++) {
            String line = lines.get(li);
            if (line.isBlank()) continue;
            String[] c = line.split(",", -1);
            Row r = new Row();
            r.name = get(c, idx, "image");
            r.category = get(c, idx, "category");
            r.hasGt = bool(get(c, idx, "has_gt"));
            r.detected = bool(get(c, idx, "detected_found"));
            r.warpPath = get(c, idx, "warp_path");
            r.nearFull = bool(get(c, idx, "near_full_frame"));
            r.aspectPlausible = bool(get(c, idx, "out_aspect_plausible"));
            r.mad = dbl(get(c, idx, "distortion_mad"));
            r.suspect = bool(get(c, idx, "distorted_suspect"));
            r.note = get(c, idx, "note");
            r.outW = (int) dbl(get(c, idx, "out_w"));
            r.outH = (int) dbl(get(c, idx, "out_h"));
            map.put(r.name, r);
        }
        return map;
    }

    private static String get(String[] c, Map<String, Integer> idx, String key) {
        Integer i = idx.get(key);
        if (i == null || i >= c.length) return "";
        return c[i].trim();
    }

    private static boolean bool(String s) {
        return s != null && (s.equalsIgnoreCase("true") || s.equals("1"));
    }

    private static double dbl(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return -1; }
    }

    private static BufferedImage readJpg(Path p) {
        if (!Files.isRegularFile(p)) return null;
        try {
            BufferedImage img = ImageIO.read(p.toFile());
            if (img == null) return null;
            // normalise to RGB so PNG output is predictable
            if (img.getType() == BufferedImage.TYPE_INT_RGB) return img;
            BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.drawImage(img, 0, 0, null);
            g.dispose();
            return rgb;
        } catch (Exception e) {
            log("  decode failed: " + p + " (" + e + ")");
            return null;
        }
    }

    private static BufferedImage scaleToFit(BufferedImage src, int maxW, int maxH) {
        double sx = maxW / (double) src.getWidth();
        double sy = maxH / (double) src.getHeight();
        double s = Math.min(1.0, Math.min(sx, sy));
        int w = Math.max(1, (int) Math.round(src.getWidth() * s));
        int h = Math.max(1, (int) Math.round(src.getHeight() * s));
        if (w == src.getWidth() && h == src.getHeight()) return src;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static void writePng(BufferedImage img, Path out) throws IOException {
        ImageIO.write(img, "png", out.toFile());
    }

    private static List<String> wrap(Graphics2D g, String text, int maxWidth) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String para : text.split("\n")) {
            StringBuilder cur = new StringBuilder();
            for (String word : para.split(" ")) {
                String cand = cur.length() == 0 ? word : cur + " " + word;
                if (g.getFontMetrics().stringWidth(cand) <= maxWidth) {
                    cur.setLength(0);
                    cur.append(cand);
                } else {
                    if (cur.length() > 0) out.add(cur.toString());
                    cur.setLength(0);
                    cur.append(word);
                }
            }
            if (cur.length() > 0) out.add(cur.toString());
        }
        return out;
    }

    private static String clip(Graphics2D g, String s, int maxWidth) {
        if (g.getFontMetrics().stringWidth(s) <= maxWidth) return s;
        String ell = "...";
        int n = s.length();
        while (n > 0 && g.getFontMetrics().stringWidth(s.substring(0, n) + ell) > maxWidth) n--;
        return s.substring(0, Math.max(0, n)) + ell;
    }

    private static String fmt(double d) {
        if (d < 0) return "n/a";
        return String.format(Locale.ROOT, "%.5f", d);
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '&') b.append('&').append("amp;");
            else if (ch == '<') b.append('&').append("lt;");
            else if (ch == '>') b.append('&').append("gt;");
            else if (ch == '"') b.append('&').append("quot;");
            else b.append(ch);
        }
        return b.toString();
    }

    private static String enc(String s) {
        // file names here are ASCII slugs; URL-encode just in case
        return s.replace(" ", "%20");
    }

    private static String rel(Path gallery, Path target) {
        try {
            return gallery.getParent().relativize(target).toString().replace('\\', '/');
        } catch (Exception e) {
            return target.toString().replace('\\', '/');
        }
    }

    private static long dirSize(Path dir) throws IOException {
        final long[] total = {0};
        Files.walk(dir).filter(Files::isRegularFile).forEach(p -> total[0] += p.toFile().length());
        return total[0];
    }

    private static String human(long bytes) {
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb);
        double mb = kb / 1024.0;
        return String.format(Locale.ROOT, "%.2f MB (%d bytes)", mb, bytes);
    }

    private static void log(String msg) {
        System.out.println("[" + LocalTime.now().format(TS) + "] " + msg);
    }
}
