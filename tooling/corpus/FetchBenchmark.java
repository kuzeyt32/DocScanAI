import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.zip.Inflater;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * DocScanAI SUBTASK H3a - host-side REAL-document benchmark acquirer.
 *
 * Downloads a bounded subset of the <b>DocUNet Benchmark</b> (Ma, Shu, Bai, Wang, Samaras - "DocUNet:
 * Document Image Unwarping via A Stacked U-Net", CVPR 2018) into the androidTest assets folder.
 * DocUNet is a purpose-built rectification benchmark: ~130 REAL photographs of document pages
 * (curled / folded / skewed, varied backgrounds and lighting) each with a flatbed-<b>scan ground
 * truth</b> of the same page - i.e. exactly the "flat scan counterpart" ground truth this subtask
 * asks for, and real photography rather than synthetic renders.
 *
 * WHY A RANGE-STREAMING ZIP READER: the upstream host only publishes ZIP archives
 * (`original.zip` 344,449,749 B, `scan.zip` 436,458,210 B). We do NOT download those archives. We
 * read the ZIP central directory with two small HTTP Range requests, then fetch ONLY the local
 * header + deflate payload of the handful of entries we keep, via further Range requests. Total
 * payload stays well inside the subtask budget. If the server ever answers 200 (range ignored)
 * instead of 206, we ABORT that source rather than pull a 300+ MB archive.
 *
 * Uses ONLY the pinned JDK 21 (java.net.http, java.util.zip, javax.imageio) - no Python, no
 * OpenCV, no new Gradle dependency, plain HTTP + file IO.
 *
 * Budgets (subtask-mandated): per-file timeout 20 s, ONE retry per file, skip on HTTP
 * 429/403/500/5xx, global network deadline (default 300 s), <= 40 files kept.
 *
 * Run with:
 *   "C:\Program Files\Java\jdk-21\bin\java.exe" tooling\corpus\FetchBenchmark.java .\DocScanAI
 *
 * Outputs:
 *   app/src/androidTest/assets/corpus/benchmark/inputs/docunet_<base>.jpg   (REAL photos)
 *   app/src/androidTest/assets/corpus/benchmark/gt_scan/docunet_<base>.jpg  (flat-scan GT)
 *   tooling/corpus/provenance_benchmark.jsonl                                  (machine readable)
 *
 * Never fabricates an image: anything that fails to download, decode or downscale is skipped and
 * reported.
 */
public class FetchBenchmark {

    // ---- budgets ------------------------------------------------------------------------------
    private static final int PER_FILE_TIMEOUT_S = 20;
    private static final long GLOBAL_DEADLINE_MS = 360_000L;   // 6 min of network activity
    private static final int MAX_PAIRS = 20;                    // 20 photos + 20 GT scans = 40 files
    private static final int MAX_LONG_EDGE = 3000;              // subtask cap
    private static final float JPEG_Q = 0.90f;

    // ---- sources ------------------------------------------------------------------------------
    private static final String BASE = "http://vision.cs.stonybrook.edu/~kema/docwarp/";
    private static final String ORIGINAL_ZIP = BASE + "original.zip";   // real photographs
    private static final String SCAN_ZIP = BASE + "scan.zip";           // flatbed-scan ground truth

    private static final String UA =
            "DocScanAI-corpus-fetcher/0.2 (instrumented-test benchmark corpus; contact: local dev)";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final long START = System.currentTimeMillis();
    private static Path PROGRESS;
    private static long totalBytes = 0;
    private static int attempts = 0, okFiles = 0, failFiles = 0;

    public static void main(String[] args) throws Exception {
        boolean listMode = false;
        boolean fixProvMode = false;
        boolean regenMode = false;
        List<String> positional = new ArrayList<>();
        for (String a : args) {
            if (a.equalsIgnoreCase("list")) listMode = true;
            else if (a.equalsIgnoreCase("fixprov")) fixProvMode = true;
            else if (a.equalsIgnoreCase("regen")) regenMode = true;
            else positional.add(a);
        }
        Path root = Paths.get(!positional.isEmpty() ? positional.get(0) : ".");
        Path assets = root.resolve("app/src/androidTest/assets/corpus/benchmark");
        Path inDir = assets.resolve("inputs");
        Path gtDir = assets.resolve("gt_scan");
        Files.createDirectories(inDir);
        Files.createDirectories(gtDir);
        Files.createDirectories(assets.resolve("gt_corners")); // reserved (DocUNet has no corner GT)
        Path provFile = root.resolve("tooling/corpus/provenance_benchmark.jsonl");
        Files.createDirectories(provFile.getParent());
        if (!fixProvMode && !regenMode) Files.writeString(provFile, "", StandardCharsets.UTF_8);
        // Default progress sink if the caller did not point us at the run dir.
        PROGRESS = Paths.get(System.getProperty("docscan.progress", root.resolve("tooling/out/benchmark_progress.txt").toString()));

        log("FetchBenchmark start: assets=" + assets.toAbsolutePath());
        log("budgets: perFile=" + PER_FILE_TIMEOUT_S + "s global=" + (GLOBAL_DEADLINE_MS / 1000) + "s maxPairs=" + MAX_PAIRS);

        long t0 = System.currentTimeMillis();
        List<ZipEntryInfo> originals = readCentralDirectory(ORIGINAL_ZIP);
        log("central directory " + ORIGINAL_ZIP + " -> " + originals.size() + " entries (" + elapsed() + ")");
        List<ZipEntryInfo> scans = readCentralDirectory(SCAN_ZIP);
        log("central directory " + SCAN_ZIP + " -> " + scans.size() + " entries (" + elapsed() + ")");

        if (listMode) {
            StringBuilder sb = new StringBuilder();
            for (ZipEntryInfo e : originals) sb.append("ORIG\t").append(e.name).append("\tm=").append(e.method)
                    .append("\tc=").append(e.compSize).append("\tu=").append(e.uncompSize).append('\n');
            for (ZipEntryInfo e : scans) sb.append("SCAN\t").append(e.name).append("\tm=").append(e.method)
                    .append("\tc=").append(e.compSize).append("\tu=").append(e.uncompSize).append('\n');
            Path dump = root.resolve("tooling/out/benchmark_entries.txt");
            Files.createDirectories(dump.getParent());
            Files.writeString(dump, sb.toString(), StandardCharsets.UTF_8);
            log("LIST written to " + dump.toAbsolutePath());
            System.out.println(sb);
            return;
        }

        if (fixProvMode) {
            // Offline repair: the first generation omitted a few commas. Insert a comma wherever a
            // closing quote is immediately followed by a quoted key (never valid in our own output).
            Path f = root.resolve("tooling/corpus/provenance_benchmark.jsonl");
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            int fixed = 0;
            List<String> outLines = new ArrayList<>();
            for (String line : lines) {
                if (line.isBlank()) continue;
                String q = line.replaceAll("\"(?=\"[A-Za-z0-9_]+\":)", "\",");
                if (!q.equals(line)) fixed++;
                outLines.add(q);
            }
            Files.writeString(f, String.join("\n", outLines) + "\n", StandardCharsets.UTF_8);
            log("FIXPROV lines=" + outLines.size() + " repaired=" + fixed + " -> " + f.toAbsolutePath());
            System.out.println("FIXPROV lines=" + outLines.size() + " repaired=" + fixed);
            return;
        }

        if (regenMode) {
            // Rebuild provenance from the acquisition log + the (cheap) remote central directories,
            // WITHOUT re-downloading any image payload. The selection is deterministic, so the
            // source/GT entry names are reproduced exactly; the geometry comes from the KEEP lines
            // the fetch run wrote into the progress log.
            Path f = root.resolve("tooling/corpus/provenance_benchmark.jsonl");
            List<String> keepDimLines = new ArrayList<>();
            if (PROGRESS != null && Files.exists(PROGRESS)) {
                for (String line : Files.readAllLines(PROGRESS, StandardCharsets.UTF_8)) {
                    int i = line.indexOf(" KEEP ");
                    if (i >= 0) keepDimLines.add(line.substring(i + 6));
                }
            }
            log("REGEN source log " + PROGRESS + " -> " + keepDimLines.size() + " KEEP line(s)");
            TreeMap<String, ZipEntryInfo> oMap = indexByBase(originals);
            TreeMap<String, ZipEntryInfo> sMap = indexByBase(scans);
            List<Integer> pages = new ArrayList<>();
            for (String k : sMap.keySet()) { try { pages.add(Integer.parseInt(k)); } catch (NumberFormatException ig) { } }
            java.util.Collections.sort(pages);
            List<Integer> pick = pickPagesEvenly(pages, Math.min(MAX_PAIRS, pages.size()));
            Path regenInDir = assets.resolve("inputs"), regenGtDir = assets.resolve("gt_scan");
            Files.writeString(f, "", StandardCharsets.UTF_8);
            int shotCounter = 0, idx = 0;
            for (Integer pageNo : pick) {
                String shot = (shotCounter++ % 2 == 0) ? "1" : "2";
                ZipEntryInfo o = oMap.get(pageNo + "_" + shot);
                if (o == null) o = oMap.get(pageNo + "_1");
                if (o == null) o = oMap.get(pageNo + "_2");
                ZipEntryInfo s = sMap.get(String.valueOf(pageNo));
                String outName = String.format(Locale.US, "docunet_%02d.jpg", pageNo);
                int[] d = idx < keepDimLines.size() ? parseDims(keepDimLines.get(idx)) : new int[8];
                long psz = sizeOrZero(regenInDir.resolve(outName));
                long ssz = sizeOrZero(regenGtDir.resolve(outName));
                appendProv(f, jsonLine(outName, "page " + pageNo + " shot " + shot, o, s,
                        d[0], d[1], d[4], d[5], d[2], d[3], d[6], d[7], psz, ssz,
                        d[0] > 0 ? transform(d[0], d[1]) : "downscaled to <= " + MAX_LONG_EDGE + " px long edge",
                        d[4] > 0 ? transform(d[4], d[5]) : "downscaled to <= " + MAX_LONG_EDGE + " px long edge",
                        "flat_scan"));
                idx++;
            }
            log("REGEN wrote " + idx + " provenance line(s) -> " + f.toAbsolutePath());
            System.out.println("REGEN lines=" + idx);
            return;
        }

        TreeMap<String, ZipEntryInfo> oMap = indexByBase(originals);
        TreeMap<String, ZipEntryInfo> sMap = indexByBase(scans);
        // DocUNet names the flatbed GT scan PER PAGE (scan/<n>.png) while the originals carry a
        // per-shot suffix (original/<n>_<shot>.jpg): the basename sets intentionally do NOT
        // intersect. Pair by PAGE NUMBER instead - pick pages evenly across the benchmark so the
        // subset spans the whole capture range, alternating the two shots for extra variety.
        List<Integer> pages = new ArrayList<>();
        for (String k : sMap.keySet()) {
            try { pages.add(Integer.parseInt(k)); } catch (NumberFormatException ignore) { }
        }
        java.util.Collections.sort(pages);
        log("scan GT pages available = " + pages.size()
                + (pages.isEmpty() ? "" : " (pages " + pages.get(0) + ".." + pages.get(pages.size() - 1) + ")"));
        if (pages.isEmpty()) {
            log("FATAL: no numeric scan pages found in scan.zip - cannot pair GT");
            System.exit(3);
        }

        List<Integer> pick = pickPagesEvenly(pages, Math.min(MAX_PAIRS, pages.size()));
        log("selected " + pick.size() + " pages: " + pick);

        int shotCounter = 0;
        for (Integer pageNo : pick) {
            if (deadlineHit()) { log("GLOBAL DEADLINE hit - stopping selection early"); break; }
            String shot = (shotCounter++ % 2 == 0) ? "1" : "2";
            ZipEntryInfo o = oMap.get(pageNo + "_" + shot);
            if (o == null) o = oMap.get(pageNo + "_1");
            if (o == null) o = oMap.get(pageNo + "_2");
            ZipEntryInfo s = sMap.get(String.valueOf(pageNo));
            if (o == null || s == null) { log("SKIP page " + pageNo + " (missing entry)"); failFiles++; continue; }
            String outName = String.format(Locale.US, "docunet_%02d.jpg", pageNo);
            String pageLabel = "page " + pageNo + " shot " + shot;
            try {
                byte[] photoBytes = fetchEntry(ORIGINAL_ZIP, o, pageLabel + " photo");
                byte[] scanBytes = fetchEntry(SCAN_ZIP, s, pageLabel + " scan");
                if (photoBytes == null || scanBytes == null) { failFiles++; continue; }

                BufferedImage photo = ImageIO.read(new ByteArrayInputStream(photoBytes));
                BufferedImage scan = ImageIO.read(new ByteArrayInputStream(scanBytes));
                if (photo == null || scan == null) {
                    log("SKIP " + pageLabel + " (undecodable: photo=" + (photo != null) + " scan=" + (scan != null) + ")");
                    failFiles++;
                    continue;
                }
                int pw = photo.getWidth(), ph = photo.getHeight();
                int sw = scan.getWidth(), sh = scan.getHeight();
                BufferedImage photoOut = downscale(photo);
                BufferedImage scanOut = downscale(scan);
                Path pj = inDir.resolve(outName);
                Path sj = gtDir.resolve(outName);
                writeJpeg(photoOut, pj);
                writeJpeg(scanOut, sj);
                long psz = Files.size(pj), ssz = Files.size(sj);
                okFiles++;
                log("KEEP " + outName + " photo " + pw + "x" + ph + " -> " + photoOut.getWidth() + "x"
                        + photoOut.getHeight() + " (" + (psz / 1024) + " KiB) | GT scan " + sw + "x" + sh
                        + " -> " + scanOut.getWidth() + "x" + scanOut.getHeight() + " (" + (ssz / 1024) + " KiB)");
                appendProv(provFile, jsonLine(outName, pageLabel, o, s, pw, ph, sw, sh,
                        photoOut.getWidth(), photoOut.getHeight(), scanOut.getWidth(), scanOut.getHeight(),
                        psz, ssz, transform(pw, ph), transform(sw, sh), "flat_scan"));
            } catch (Exception e) {
                failFiles++;
                log("ERROR " + pageLabel + " :: " + e);
                appendProv(provFile, jsonLine(outName, pageLabel, o, s, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                        "error: " + e.getMessage(), "error: " + e.getMessage(), "none"));
            }
        }

        long dt = System.currentTimeMillis() - t0;
        log("FetchBenchmark DONE kept=" + okFiles + " failed=" + failFiles + " attempts=" + attempts
                + " totalBytes=" + totalBytes + " (" + (totalBytes / 1024 / 1024) + " MiB) networkMs=" + dt);
        System.out.println("[FetchBenchmark] DONE kept=" + okFiles + " failed=" + failFiles
                + " totalBytes=" + totalBytes + " payloadMiB=" + (totalBytes / 1024 / 1024)
                + " networkMs=" + dt);
    }

    // ==============================================================================================
    // ZIP central-directory reader (Range based)
    // ==============================================================================================

    static final class ZipEntryInfo {
        String name; int method; long compSize; long uncompSize; long localOffset;
        public String toString() { return name + " (m=" + method + ", c=" + compSize + ")"; }
    }

    /** Reads the EOCD + central directory of a REMOTE zip using two small Range requests. */
    static List<ZipEntryInfo> readCentralDirectory(String url) throws Exception {
        long size = remoteSize(url);
        if (size <= 0) throw new IllegalStateException("cannot determine size of " + url);

        long tailLen = Math.min(size, 66_000L);
        byte[] tail = requireRange(url, size - tailLen, size - 1, "EOCD tail");
        int eocd = -1;
        for (int i = tail.length - 22; i >= 0; i--) {
            if ((tail[i] & 0xFF) == 0x50 && (tail[i + 1] & 0xFF) == 0x4B
                    && (tail[i + 2] & 0xFF) == 0x05 && (tail[i + 3] & 0xFF) == 0x06) { eocd = i; break; }
        }
        if (eocd < 0) throw new IllegalStateException("EOCD not found in " + url);
        long cdSize = u32(tail, eocd + 12);
        long cdOffset = u32(tail, eocd + 16);
        int entries = u16(tail, eocd + 10);
        log("  EOCD: entries=" + entries + " cdSize=" + cdSize + " cdOffset=" + cdOffset);
        if (cdOffset == 0xFFFFFFFFL || entries == 0xFFFF) {
            throw new IllegalStateException("ZIP64 central directory unsupported/needed for " + url);
        }

        byte[] cd = requireRange(url, cdOffset, cdOffset + cdSize - 1, "central directory");
        List<ZipEntryInfo> out = new ArrayList<>();
        int p = 0;
        while (p + 46 <= cd.length) {
            if (!(u32(cd, p) == 0x02014b50L)) break;
            ZipEntryInfo e = new ZipEntryInfo();
            e.method = u16(cd, p + 10);
            e.compSize = u32(cd, p + 20);
            e.uncompSize = u32(cd, p + 24);
            int nameLen = u16(cd, p + 28);
            int extraLen = u16(cd, p + 30);
            int commentLen = u16(cd, p + 32);
            e.localOffset = u32(cd, p + 42);
            e.name = new String(cd, p + 46, nameLen, StandardCharsets.UTF_8);
            out.add(e);
            p += 46 + nameLen + extraLen + commentLen;
        }
        return out;
    }

    /** Fetches the deflate payload of ONE zip entry and returns the inflated bytes. */
    static byte[] fetchEntry(String url, ZipEntryInfo e, String what) {
        if (e == null) { log("SKIP " + what + " (no entry)"); return null; }
        if (deadlineHit()) { log("SKIP " + what + " (global deadline)"); return null; }
        try {
            byte[] lh = requireRange(url, e.localOffset, e.localOffset + 29, "local header " + what);
            if (lh == null) return null;
            int nameLen = u16(lh, 26);
            int extraLen = u16(lh, 28);
            long dataStart = e.localOffset + 30 + nameLen + extraLen;
            if (e.compSize <= 0 || e.compSize > 64L * 1024 * 1024) {
                log("SKIP " + what + " (implausible compressed size " + e.compSize + ")"); return null;
            }
            byte[] comp = requireRange(url, dataStart, dataStart + e.compSize - 1, "payload " + what);
            if (comp == null) return null;
            if (e.method == 0) return comp;                         // STORED
            if (e.method != 8) { log("SKIP " + what + " (method " + e.method + ")"); return null; }
            Inflater inf = new Inflater(true);
            try {
                inf.setInput(comp);
                byte[] out = new byte[(int) e.uncompSize];
                int n = inf.inflate(out);
                if (n != out.length) {
                    byte[] trimmed = new byte[n];
                    System.arraycopy(out, 0, trimmed, 0, n);
                    return trimmed;
                }
                return out;
            } finally {
                inf.end();
            }
        } catch (Exception ex) {
            log("SKIP " + what + " (exception " + ex + ")");
            return null;
        }
    }

    // ==============================================================================================
    // HTTP Range plumbing (budgeted: 20 s per file, ONE retry, skip on 429/403/5xx)
    // ==============================================================================================

    /** Range GET that must return 206; a 200 means the server ignored Range -> abort, never stream it. */
    private static byte[] requireRange(String url, long start, long end, String what) {
        byte[] b = rangeGet(url, start, end, what, true);
        return b;
    }

    private static byte[] rangeGet(String url, long start, long end, String what, boolean require206) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            if (deadlineHit()) { log("SKIP " + what + " (global deadline)"); return null; }
            attempts++;
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", UA)
                        .header("Range", "bytes=" + start + "-" + end)
                        .timeout(Duration.ofSeconds(PER_FILE_TIMEOUT_S))
                        .GET().build();
                HttpResponse<byte[]> r = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
                int code = r.statusCode();
                byte[] body = r.body();
                if (code == 206 || (code == 200 && !require206)) {
                    totalBytes += body.length;
                    log("GET " + what + " range=" + start + "-" + end + " http=" + code
                            + " bytes=" + body.length + " total=" + totalBytes);
                    return body;
                }
                if (code == 200) {
                    log("ABORT " + what + ": server ignored Range (http=200, would stream "
                            + body.length + " B) - refusing to pull the whole archive");
                    return null;
                }
                log("GET " + what + " http=" + code + " attempt=" + attempt + " bytes=" + body.length
                        + " result=SKIP");
                if (code == 429 || code == 403 || code == 500 || code / 100 == 5 || code == 416) return null;
                // other 4xx: one retry then give up
            } catch (Exception ex) {
                log("GET " + what + " attempt=" + attempt + " exception=" + ex + " result=RETRY");
            }
        }
        return null;
    }

    /** Total size of a remote resource via a 1-byte Range probe (Content-Range total). */
    private static long remoteSize(String url) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", UA)
                        .header("Range", "bytes=0-0")
                        .timeout(Duration.ofSeconds(PER_FILE_TIMEOUT_S))
                        .GET().build();
                HttpResponse<byte[]> r = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
                totalBytes += r.body().length;
                String cr = r.headers().firstValue("content-range").orElse("");
                if (cr.contains("/")) {
                    long total = Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim());
                    log("PROBE " + url + " http=" + r.statusCode() + " content-range=" + cr + " total=" + total);
                    return total;
                }
                String cl = r.headers().firstValue("content-length").orElse("");
                if (!cl.isEmpty()) { log("PROBE " + url + " http=" + r.statusCode() + " length=" + cl); return Long.parseLong(cl.trim()); }
                log("PROBE " + url + " http=" + r.statusCode() + " no size header");
            } catch (Exception ex) {
                log("PROBE " + url + " attempt=" + attempt + " exception=" + ex);
            }
        }
        return -1;
    }

    // ==============================================================================================
    // Selection + helpers
    // ==============================================================================================

    private static TreeMap<String, ZipEntryInfo> indexByBase(List<ZipEntryInfo> list) {
        TreeMap<String, ZipEntryInfo> m = new TreeMap<>();
        for (ZipEntryInfo e : list) {
            if (e.name.endsWith("/")) continue;
            String f = e.name.substring(e.name.lastIndexOf('/') + 1);
            int dot = f.lastIndexOf('.');
            String base = dot > 0 ? f.substring(0, dot) : f;
            String ext = dot > 0 ? f.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
            if (!(ext.equals("jpg") || ext.equals("jpeg") || ext.equals("png"))) continue;
            m.putIfAbsent(base, e);
        }
        return m;
    }

    /** Evenly spaced pick => spread across the whole benchmark (variety of capture conditions). */
    private static List<String> pickEvenly(List<String> all, int n) {
        List<String> out = new ArrayList<>();
        if (n <= 0) return out;
        if (n >= all.size()) return new ArrayList<>(all);
        for (int i = 0; i < n; i++) {
            int idx = (int) Math.round(i * (all.size() - 1.0) / (n - 1.0));
            String v = all.get(Math.max(0, Math.min(all.size() - 1, idx)));
            if (!out.contains(v)) out.add(v);
        }
        return out;
    }

    /** Pulls the 8 dimensions (src/out x photo/scan) out of a KEEP log line, in encounter order. */
    private static int[] parseDims(String s) {
        int[] out = new int[8];
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)x(\\d+)").matcher(s == null ? "" : s);
        int i = 0;
        while (m.find() && i + 1 < 8) {
            out[i++] = Integer.parseInt(m.group(1));
            out[i++] = Integer.parseInt(m.group(2));
        }
        return out;
    }

    private static long sizeOrZero(Path p) {
        try { return Files.size(p); } catch (Exception e) { return 0L; }
    }

    /** Evenly spaced page pick (integers) - same sampling idea along the page axis. */
    private static List<Integer> pickPagesEvenly(List<Integer> all, int n) {
        List<Integer> out = new ArrayList<>();
        if (n <= 0) return out;
        if (n >= all.size()) return new ArrayList<>(all);
        for (int i = 0; i < n; i++) {
            int idx = (int) Math.round(i * (all.size() - 1.0) / (n - 1.0));
            Integer v = all.get(Math.max(0, Math.min(all.size() - 1, idx)));
            if (!out.contains(v)) out.add(v);
        }
        return out;
    }

    private static String sanitize(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
            else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') sb.append('_');
        }
        return sb.toString().replaceAll("_+$", "");
    }

    private static boolean deadlineHit() { return System.currentTimeMillis() - START > GLOBAL_DEADLINE_MS; }

    private static String elapsed() { return ((System.currentTimeMillis() - START) / 1000) + "s"; }

    private static String transform(int w, int h) {
        int le = Math.max(w, h);
        return le > MAX_LONG_EDGE
                ? "bilinear downscale long edge " + le + " -> " + MAX_LONG_EDGE + " px"
                : "none (long edge " + le + " <= " + MAX_LONG_EDGE + " px)";
    }

    private static BufferedImage downscale(BufferedImage src) {
        int le = Math.max(src.getWidth(), src.getHeight());
        if (le <= MAX_LONG_EDGE) return src;
        double s = (double) MAX_LONG_EDGE / le;
        int nw = Math.max(1, (int) Math.round(src.getWidth() * s));
        int nh = Math.max(1, (int) Math.round(src.getHeight() * s));
        BufferedImage dst = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return dst;
    }

    private static void writeJpeg(BufferedImage img, Path target) throws Exception {
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
        p.setCompressionQuality(JPEG_Q);
        try (OutputStream os = Files.newOutputStream(target);
             ImageOutputStream ios = ImageIO.createImageOutputStream(os)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(rgb, null, null), p);
        } finally {
            writer.dispose();
        }
    }

    private static int u16(byte[] b, int off) { return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8); }

    private static long u32(byte[] b, int off) {
        return ((long) (b[off] & 0xFF)) | ((long) (b[off + 1] & 0xFF) << 8)
                | ((long) (b[off + 2] & 0xFF) << 16) | ((long) (b[off + 3] & 0xFF) << 24);
    }

    // ==============================================================================================
    // progress + provenance
    // ==============================================================================================

    private static void log(String msg) {
        String line = "[" + LocalTime.now().format(HMS) + "] " + msg;
        System.out.println(line);
        try {
            if (PROGRESS != null && PROGRESS.getParent() != null) Files.createDirectories(PROGRESS.getParent());
            Files.writeString(PROGRESS, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignore) { /* progress logging must never break the fetch */ }
    }

    private static void appendProv(Path f, String line) throws Exception {
        Files.writeString(f, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String jsonLine(String file, String base, ZipEntryInfo o, ZipEntryInfo s,
                                   int ow, int oh, int sw, int sh, int nw, int nh, int gw, int gh,
                                   long psz, long ssz, String pTransform, String sTransform, String gtKind) {
        StringBuilder b = new StringBuilder("{");
        kv(b, "file", file); b.append(',');
        kv(b, "dataset", "DocUNet Benchmark (Ma et al., CVPR 2018)"); b.append(',');
        kv(b, "datasetPage", "https://www3.cs.stonybrook.edu/~cvl/docunet.html"); b.append(',');
        kv(b, "sourceArchive", ORIGINAL_ZIP); b.append(',');
        kv(b, "sourceEntry", o == null ? "" : o.name); b.append(',');
        kv(b, "gtArchive", SCAN_ZIP); b.append(',');
        kv(b, "gtEntry", s == null ? "" : s.name); b.append(',');
        kv(b, "gtKind", gtKind); b.append(',');
        kv(b, "rightsHolder", "Ke Ma / Stony Brook University Computer Vision Lab"); b.append(',');
        kv(b, "license", "Academic benchmark - research/evaluation use; no explicit license stated on the project page; NOT redistributed (local instrumented testing only)"); b.append(',');
        b.append("\"srcPhotoW\":").append(ow).append(',');
        b.append("\"srcPhotoH\":").append(oh).append(',');
        b.append("\"srcScanW\":").append(sw).append(',');
        b.append("\"srcScanH\":").append(sh).append(',');
        b.append("\"outPhotoW\":").append(nw).append(',');
        b.append("\"outPhotoH\":").append(nh).append(',');
        b.append("\"outScanW\":").append(gw).append(',');
        b.append("\"outScanH\":").append(gh).append(',');
        b.append("\"photoBytes\":").append(psz).append(',');
        b.append("\"scanBytes\":").append(ssz).append(',');
        kv(b, "photoTransform", pTransform); b.append(',');
        kv(b, "scanTransform", sTransform); b.append(',');
        kv(b, "retrieval", "HTTP Range: central-directory + per-entry local header/payload (no full-archive download)"); b.append(',');
        b.append("\"baseName\":\"").append(esc(base)).append('"');
        b.append('}');
        return b.toString();
    }

    private static void kv(StringBuilder b, String k, String v) {
        b.append('"').append(k).append("\":\"").append(esc(v)).append('"');
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
