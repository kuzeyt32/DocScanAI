import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Iterator;
import java.util.Locale;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * DocScanAI SUBTASK H1 - host-side real-photo corpus fetcher.
 *
 * Downloads a small, curated set of permissively licensed (CC0 / CC-BY / Public Domain) real-world
 * document photographs from Wikimedia Commons into the androidTest assets folder, downsizes them so
 * the long edge is <= 3000 px, and records full provenance (source URL, author, license, applied
 * transformation) into a machine-readable JSONL file.
 *
 * Uses ONLY the pinned JDK 21 (java.net.http + javax.imageio) - no Python, no OpenCV, no new
 * Gradle dependency. Run with:
 *
 *   "C:\Program Files\Java\jdk-21\bin\java.exe" tooling\corpus\FetchCorpus.java .\DocScanAI
 *
 * If a candidate is non-permissive, is not a bitmap photo, or fails to download, it is skipped and
 * reported - the program never fabricates an image.
 */
public class FetchCorpus {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private static final String API = "https://commons.wikimedia.org/w/api.php";
    /** Request a 2400 px-wide thumbnail (<= 3000 px cap) to keep the packaged corpus modest. */
    private static final int REQUEST_WIDTH = 2400;
    /** Hard cap on the packaged long edge (task requirement). */
    private static final int MAX_LONG_EDGE = 3000;

    /** Curated candidates: receipts/invoices, spiral & book pages, whiteboard, forms, tickets. */
    private static final String[] TITLES = {
            "File:ReceiptSwiss.jpg",
            "File:Starbucks Hanzhong Store invoice QT35958794.jpg",
            "File:KFC Linkou Wenhua uniform invoice 20120727.jpg",
            "File:EFTA00002964-Construction-Yard - Spiral-bound notebook with CONSTRUCTION YARD written on its lined pages rests on gravel next to a hiking boot.jpg",
            "File:(Sherwin Carlquist field notebook from 1967 expedition to Hawaii) - DPLA - 79d7fb6852e60df9f8bdabe0c443b9c2 (page 20).jpg",
            "File:Open Internal Pages of the Book Detrás del espejo 08.png",
            "File:Media Viewer Launch Retrospective Whiteboard - April 11 2014 01.jpg",
            "File:HK 上環 Sheung Wan 棟記 Tung Kee Restaurant food order list form night Nov-2013 01.JPG",
            "File:US Navy 050420-N-3503M-001 Lt. Mark Hathcock fills out the paper work needed to pay his road tax on board Naval Air Facility Atsugi, Japan.jpg",
            "File:DR Deutsche Reichsbahn computer printed ticket, Aug 1988. S-Bahn Tarif .Dresden - Bad Schandau - Flickr - sludgegulper.jpg",
            "File:CPCCT Baochangkeng Station uniform invoice 20120508.jpg",
            "File:A woman fills out a Bear Information Management form after having an encounter with a sow and her two yearling cubs at the Igloo (10d56c6e-a0f4-426b-ab8f-d89f863dc19b).JPG"
    };

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : ".");
        Path outDir = root.resolve("app/src/androidTest/assets/corpus/real");
        Files.createDirectories(outDir);
        Path provFile = root.resolve("tooling/corpus/provenance_real.jsonl");
        Files.createDirectories(provFile.getParent());
        // Fresh provenance file for this run.
        Files.writeString(provFile, "", StandardCharsets.UTF_8);

        int ok = 0, fail = 0;
        long totalBytes = 0;
        boolean first = true;
        for (String title : TITLES) {
            if (!first) Thread.sleep(2500); // be polite: avoid Wikimedia 429 throttling
            first = false;
            try {
                long written = fetchOne(root, title, outDir, provFile);
                if (written > 0) {
                    ok++;
                    totalBytes += written;
                } else {
                    fail++;
                }
            } catch (Exception e) {
                fail++;
                System.err.println("[FetchCorpus] FAIL " + title + " :: " + e);
                appendProv(provFile, jsonLine(title, "", "", "", "", "", "", 0, 0, 0, 0,
                        "exception: " + e.getMessage()));
            }
        }
        System.out.println("[FetchCorpus] DONE ok=" + ok + " fail=" + fail
                + " totalBytes=" + totalBytes + " (" + (totalBytes / 1024 / 1024) + " MiB)");
    }

    private static long fetchOne(Path root, String title, Path outDir, Path provFile) throws Exception {
        String apiUrl = API + "?action=query&format=json&prop=imageinfo"
                + "&iiprop=url%7Csize%7Cextmetadata%7Cmime&iiurlwidth=" + REQUEST_WIDTH
                + "&titles=" + enc(title);
        String json = get(apiUrl);
        if (json.contains("\"missing\"")) {
            System.out.println("[FetchCorpus] MISSING " + title);
            appendProv(provFile, jsonLine(title, "", "", "", "", "", "", 0, 0, 0, 0, "missing-on-commons"));
            return 0;
        }
        // Restrict parsing to the imageinfo block.
        int ii = json.indexOf("\"imageinfo\":[");
        String block = ii >= 0 ? json.substring(ii) : json;

        String mime = valueAfter(block, "\"mime\":\"");
        String origUrl = valueAfter(block, "\"url\":\"");
        String thumbUrl = valueAfter(block, "\"thumburl\":\"");
        int srcW = intField(block, "\"width\":");
        int srcH = intField(block, "\"height\":");
        String license = firstNonEmpty(
                valueAfter(block, "\"LicenseShortName\":{\"value\":\""),
                valueAfter(block, "\"License\":{\"value\":\""));
        String author = stripHtml(valueAfter(block, "\"Artist\":{\"value\":\""));

        if (mime == null || !(mime.equals("image/jpeg") || mime.equals("image/png"))) {
            System.out.println("[FetchCorpus] SKIP " + title + " (mime=" + mime + ")");
            appendProv(provFile, jsonLine(title, "", origUrl == null ? "" : origUrl, "", author == null ? "" : author,
                    license == null ? "" : license, "", srcW, srcH, 0, 0, "skipped-non-bitmap-photo"));
            return 0;
        }
        if (!permissive(license)) {
            System.out.println("[FetchCorpus] SKIP " + title + " (license=" + license + ")");
            appendProv(provFile, jsonLine(title, "", origUrl == null ? "" : origUrl, "", author == null ? "" : author,
                    license == null ? "" : license, "", srcW, srcH, 0, 0, "skipped-non-permissive-license"));
            return 0;
        }

        String dlUrl = (thumbUrl != null && !thumbUrl.isEmpty() && !thumbUrl.contains("\"value\""))
                ? thumbUrl : origUrl;
        if (dlUrl == null || dlUrl.isEmpty()) {
            appendProv(provFile, jsonLine(title, "", "", "", author == null ? "" : author,
                    license == null ? "" : license, "", srcW, srcH, 0, 0, "no-download-url"));
            return 0;
        }

        byte[] bytes = getBytes(dlUrl);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        if (img == null) {
            System.out.println("[FetchCorpus] UNDECODABLE " + title);
            appendProv(provFile, jsonLine(title, "", origUrl == null ? "" : origUrl, "", author == null ? "" : author,
                    license == null ? "" : license, dlUrl, srcW, srcH, 0, 0, "undecodable-image"));
            return 0;
        }

        int w = img.getWidth(), h = img.getHeight();
        BufferedImage out = img;
        String transform = "none (already <= " + MAX_LONG_EDGE + " px long edge); downloaded via Commons thumb";
        int longEdge = Math.max(w, h);
        if (longEdge > MAX_LONG_EDGE) {
            double s = (double) MAX_LONG_EDGE / longEdge;
            int nw = (int) Math.round(w * s), nh = (int) Math.round(h * s);
            out = scale(img, nw, nh);
            transform = "bilinear downscale long edge " + longEdge + " -> " + MAX_LONG_EDGE + " px";
        }

        String slug = slugify(title);
        Path jpg = outDir.resolve(slug + ".jpg");
        writeJpeg(out, jpg, 0.90f);
        long size = Files.size(jpg);
        String pageUrl = "https://commons.wikimedia.org/wiki/" + title.replace(' ', '_');
        System.out.println("[FetchCorpus] OK " + title + " -> " + jpg.getFileName()
                + " (" + srcW + "x" + srcH + " -> " + out.getWidth() + "x" + out.getHeight()
                + ", " + (size / 1024) + " KiB, " + license + ")");
        appendProv(provFile, jsonLine(title, pageUrl, origUrl == null ? "" : origUrl,
                dlUrl, author == null ? "" : author, license == null ? "" : license,
                slug + ".jpg", srcW, srcH, out.getWidth(), out.getHeight(), transform));
        return size;
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static final String UA =
            "DocScanAI-corpus-fetcher/0.2 (instrumented-test corpus; contact: local dev)";

    private static String get(String url) throws Exception {
        return new String(getBytes(url), StandardCharsets.UTF_8);
    }

    /**
     * GET with retry/backoff. Wikimedia throttles bursts with HTTP 429 (and occasionally 5xx); we
     * honour the Retry-After header when present, otherwise back off exponentially. Never hammers.
     */
    private static byte[] getBytes(String url) throws Exception {
        int attempt = 0;
        while (true) {
            attempt++;
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", UA)
                    .header("Accept", "*/*")
                    .timeout(Duration.ofSeconds(120)).GET().build();
            HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            int code = resp.statusCode();
            if (code / 100 == 2) return resp.body();
            if ((code == 429 || code >= 500) && attempt < 7) {
                long wait = retryAfterMs(resp).orElse(Math.min(30_000L, 1500L << (attempt - 1)));
                System.err.println("[FetchCorpus] HTTP " + code + " retry " + attempt
                        + " in " + wait + "ms :: " + url);
                Thread.sleep(wait);
                continue;
            }
            throw new RuntimeException("HTTP " + code + " for " + url);
        }
    }

    private static java.util.Optional<Long> retryAfterMs(HttpResponse<?> resp) {
        return resp.headers().firstValue("retry-after").map(v -> {
            try {
                return Long.parseLong(v.trim()) * 1000L;
            } catch (NumberFormatException e) {
                return 5000L;
            }
        });
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Extracts the unescaped JSON string value that follows literal [marker]. */
    private static String valueAfter(String json, String marker) {
        int i = json.indexOf(marker);
        if (i < 0) return null;
        int start = i + marker.length();
        StringBuilder sb = new StringBuilder();
        for (int k = start; k < json.length(); k++) {
            char c = json.charAt(k);
            if (c == '\\' && k + 1 < json.length()) {
                char n = json.charAt(k + 1);
                if (n == 'n') sb.append('\n');
                else if (n == 't') sb.append('\t');
                else sb.append(n);
                k++;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static int intField(String json, String marker) {
        int i = json.indexOf(marker);
        if (i < 0) return 0;
        int k = i + marker.length();
        StringBuilder sb = new StringBuilder();
        while (k < json.length() && Character.isDigit(json.charAt(k))) sb.append(json.charAt(k++));
        return sb.length() == 0 ? 0 : Integer.parseInt(sb.toString());
    }

    private static boolean permissive(String license) {
        if (license == null) return false;
        String l = license.toLowerCase(Locale.ROOT);
        return l.contains("cc0") || l.contains("cc by") || l.contains("cc-by")
                || l.contains("public domain") || l.contains("attribution")
                || l.contains("pdm") || l.equals("pd");
    }

    private static String stripHtml(String s) {
        if (s == null) return "";
        // Entities are built from unicode escapes so the literal '&xxx;' forms never appear here.
        return s.replaceAll("<[^>]*>", "")
                .replace("\u0026amp;", "\u0026")
                .replace("\u0026quot;", "\"")
                .replace("\u0026#39;", "'")
                .replace("\u0026nbsp;", " ")
                .trim();
    }

    private static String firstNonEmpty(String... vals) {
        for (String v : vals) if (v != null && !v.isEmpty()) return v;
        return "";
    }

    private static BufferedImage scale(BufferedImage src, int w, int h) {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return dst;
    }

    private static void writeJpeg(BufferedImage img, Path target, float quality) throws Exception {
        BufferedImage rgb = img;
        if (img.getType() != BufferedImage.TYPE_INT_RGB) {
            rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.drawImage(img, 0, 0, null);
            g.dispose();
        }
        Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg");
        ImageWriter writer = it.next();
        ImageWriteParam p = writer.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(quality);
        try (OutputStream os = Files.newOutputStream(target);
             ImageOutputStream ios = ImageIO.createImageOutputStream(os)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(rgb, null, null), p);
        } finally {
            writer.dispose();
        }
    }

    private static String slugify(String title) {
        String t = title.replaceFirst("^File:", "").replaceFirst("\\.[A-Za-z0-9]+$", "");
        StringBuilder sb = new StringBuilder();
        for (char c : t.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') sb.append(c);
            else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') sb.append('_');
        }
        String s = sb.toString();
        if (s.length() > 48) s = s.substring(0, 48);
        return s.replaceAll("_+$", "");
    }

    private static String jsonLine(String title, String pageUrl, String origUrl, String dlUrl,
                                   String author, String license, String file, int sw, int sh, int ow, int oh,
                                   String transform) {
        StringBuilder b = new StringBuilder("{");
        kv(b, "title", title); b.append(',');
        kv(b, "commonsPage", pageUrl); b.append(',');
        kv(b, "originalUrl", origUrl); b.append(',');
        kv(b, "downloadUrl", dlUrl); b.append(',');
        kv(b, "author", author); b.append(',');
        kv(b, "license", license); b.append(',');
        kv(b, "file", file); b.append(',');
        b.append("\"srcWidth\":").append(sw).append(',');
        b.append("\"srcHeight\":").append(sh).append(',');
        b.append("\"outWidth\":").append(ow).append(',');
        b.append("\"outHeight\":").append(oh).append(',');
        kv(b, "transform", transform);
        b.append('}');
        return b.toString();
    }

    private static void kv(StringBuilder b, String k, String v) {
        b.append('"').append(k).append("\":\"").append(esc(v)).append('"');
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void appendProv(Path provFile, String line) throws Exception {
        Files.writeString(provFile, line + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
