package com.quietpages.quietpages.util;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extracts an EPUB file to a temporary directory and provides:
 * - Ordered list of chapters (spine items) with their file paths
 * - Table of contents (NavPoint tree)
 * - Page content as file:// URLs suitable for WebView
 *
 * EPUB = ZIP containing HTML/XHTML chapters + CSS + images + OPF manifest.
 * We extract everything to a temp folder and serve file:// URLs directly —
 * this means images, CSS, and fonts all load correctly in WebView.
 */
public class EpubRenderer {

    // ── Data classes ──────────────────────────────────────────────────────────

    public static class Chapter {
        public final String id;
        public final String title;
        public final String filePath; // absolute path to extracted HTML file
        public final String fileUrl; // file:// URL for WebView

        public Chapter(String id, String title, String filePath) {
            this.id = id;
            this.title = title;
            this.filePath = filePath;
            this.fileUrl = new File(filePath).toURI().toString();
        }

        @Override
        public String toString() {
            return title;
        }
    }

    public static class TocEntry {
        public final String title;
        public final String src; // href within the EPUB (may include #anchor)
        public final String resolvedUrl; // file:// URL for WebView
        public final List<TocEntry> children = new ArrayList<>();
        public final int playOrder;

        public TocEntry(String title, String src, String resolvedUrl, int playOrder) {
            this.title = title;
            this.src = src;
            this.resolvedUrl = resolvedUrl;
            this.playOrder = playOrder;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    private final File epubFile;
    private Path extractDir;
    private String opfDir = ""; // directory containing the OPF file inside ZIP
    private final List<Chapter> spine = new ArrayList<>();
    private final List<TocEntry> toc = new ArrayList<>();
    private String bookTitle = "";
    private boolean loaded = false;

    // ── Constructor ───────────────────────────────────────────────────────────

    public EpubRenderer(File epubFile) {
        this.epubFile = epubFile;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Extracts the EPUB and parses its structure.
     * Call once before using any other methods.
     */
    public void load() throws IOException {
        if (loaded)
            return;

        // Create a stable temp dir named after the book file
        String safeName = epubFile.getName().replaceAll("[^a-zA-Z0-9]", "_");
        extractDir = Path.of(System.getProperty("java.io.tmpdir"),
                "QuietPages_reader", safeName);
        Files.createDirectories(extractDir);

        // Extract ZIP contents
        try (ZipFile zip = new ZipFile(epubFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path dest = extractDir.resolve(entry.getName()).normalize();
                if (!dest.startsWith(extractDir))
                    continue; // zip slip protection
                if (entry.isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    try (InputStream is = zip.getInputStream(entry)) {
                        Files.write(dest, is.readAllBytes(),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING);
                    }
                }
            }
        }

        // Parse structure
        String opfPath = findOpfPath();
        if (opfPath != null) {
            opfDir = opfPath.contains("/")
                    ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1)
                    : "";
            String opfContent = readFile(extractDir.resolve(opfPath));
            parseOpf(opfContent);
            parseToc(opfContent);
        }

        loaded = true;
    }

    public List<Chapter> getSpine() {
        return Collections.unmodifiableList(spine);
    }

    public List<TocEntry> getToc() {
        return Collections.unmodifiableList(toc);
    }

    public String getBookTitle() {
        return bookTitle;
    }

    public boolean isLoaded() {
        return loaded;
    }

    /**
     * Returns the file:// URL for a given spine index.
     */
    public String getChapterUrl(int spineIndex) {
        if (spineIndex < 0 || spineIndex >= spine.size())
            return null;
        return spine.get(spineIndex).fileUrl;
    }

    /**
     * Finds which spine index contains the given file URL (ignoring anchors).
     */
    public int findSpineIndex(String fileUrl) {
        String base = fileUrl.split("#")[0];
        for (int i = 0; i < spine.size(); i++) {
            if (spine.get(i).fileUrl.equals(base))
                return i;
        }
        return 0;
    }

    public int getTotalChapters() {
        return spine.size();
    }

    /**
     * Injects a reader stylesheet into a chapter HTML file so we can control
     * font, colors, margins, line-height etc. without modifying the EPUB.
     * Returns the injected content as a data: URL.
     */
    public String getChapterUrlWithTheme(int spineIndex, ReaderTheme theme) throws IOException {
        if (spineIndex < 0 || spineIndex >= spine.size())
            return null;
        Chapter ch = spine.get(spineIndex);
        String html = readFile(Path.of(ch.filePath));

        // Inject our CSS just before </head>
        String css = buildReaderCss(theme);
        String injection = "<style id='qp-reader-style'>" + css + "</style>";

        if (html.contains("</head>")) {
            html = html.replace("</head>", injection + "</head>");
        } else {
            html = injection + html;
        }

        // Return as a data URL so WebView doesn't need to reload from disk
        // BUT we need the base URL to be the chapter file so relative
        // paths (images, CSS) resolve correctly.
        // We write the modified HTML back to a temp file instead.
        Path themed = extractDir.resolve("_themed_" + spineIndex + ".html");
        Files.writeString(themed, html, StandardCharsets.UTF_8);
        return themed.toFile().toURI().toString();
    }

    public void cleanup() {
        if (extractDir != null) {
            try {
                deleteDirectory(extractDir);
            } catch (IOException ignored) {
            }
        }
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private String findOpfPath() throws IOException {
        Path container = extractDir.resolve("META-INF/container.xml");
        if (!Files.exists(container))
            return null;
        String content = readFile(container);
        int idx = content.indexOf("full-path=\"");
        if (idx < 0)
            return null;
        int start = idx + 11;
        int end = content.indexOf('"', start);
        return end > start ? content.substring(start, end) : null;
    }

    private void parseOpf(String opf) {
        // Book title
        bookTitle = tagValue(opf, "dc:title", epubFile.getName());

        // Build id→href map from manifest
        Map<String, String> idToHref = new LinkedHashMap<>();
        Map<String, String> idToMediaType = new HashMap<>();
        String manifest = block(opf, "<manifest", "</manifest>");
        if (manifest != null) {
            for (String item : manifest.split("<item ")) {
                String id = attr(item, "id");
                String href = attr(item, "href");
                String mediaType = attr(item, "media-type");
                if (id != null && href != null) {
                    idToHref.put(id, href);
                    if (mediaType != null)
                        idToMediaType.put(id, mediaType);
                }
            }
        }

        // Walk spine to build ordered chapter list
        String spineBlock = block(opf, "<spine", "</spine>");
        if (spineBlock != null) {
            String[] items = spineBlock.split("<itemref ");
            for (String item : items) {
                String idref = attr(item, "idref");
                if (idref == null)
                    continue;
                String href = idToHref.get(idref);
                if (href == null)
                    continue;
                String mediaType = idToMediaType.getOrDefault(idref, "");
                if (!mediaType.contains("html") && !mediaType.contains("xhtml")
                        && !href.toLowerCase().endsWith(".html")
                        && !href.toLowerCase().endsWith(".xhtml")
                        && !href.toLowerCase().endsWith(".htm"))
                    continue;

                Path absPath = extractDir.resolve(opfDir + href).normalize();
                if (Files.exists(absPath)) {
                    spine.add(new Chapter(idref, titleFromHref(href),
                            absPath.toString()));
                }
            }
        }
    }

    private void parseToc(String opf) {
        // Try EPUB3 nav.xhtml first, then fall back to EPUB2 ncx
        String navHref = findNavHref(opf);
        if (navHref != null) {
            Path navPath = extractDir.resolve(opfDir + navHref).normalize();
            if (Files.exists(navPath)) {
                try {
                    parseNavXhtml(readFile(navPath), navPath.getParent());
                    if (!toc.isEmpty())
                        return;
                } catch (IOException ignored) {
                }
            }
        }

        // EPUB2 NCX
        String ncxHref = findNcxHref(opf);
        if (ncxHref != null) {
            Path ncxPath = extractDir.resolve(opfDir + ncxHref).normalize();
            if (Files.exists(ncxPath)) {
                try {
                    parseNcx(readFile(ncxPath), ncxPath.getParent());
                } catch (IOException ignored) {
                }
            }
        }

        // Fallback: use spine as TOC
        if (toc.isEmpty()) {
            for (int i = 0; i < spine.size(); i++) {
                Chapter ch = spine.get(i);
                toc.add(new TocEntry(ch.title, ch.filePath, ch.fileUrl, i));
            }
        }
    }

    private String findNavHref(String opf) {
        String manifest = block(opf, "<manifest", "</manifest>");
        if (manifest == null)
            return null;
        for (String item : manifest.split("<item ")) {
            String props = attr(item, "properties");
            if (props != null && props.contains("nav")) {
                return attr(item, "href");
            }
        }
        return null;
    }

    private String findNcxHref(String opf) {
        String manifest = block(opf, "<manifest", "</manifest>");
        if (manifest == null)
            return null;
        for (String item : manifest.split("<item ")) {
            String mt = attr(item, "media-type");
            if (mt != null && mt.contains("ncx"))
                return attr(item, "href");
        }
        // Fallback: look for .ncx extension
        if (manifest != null) {
            for (String item : manifest.split("<item ")) {
                String href = attr(item, "href");
                if (href != null && href.endsWith(".ncx"))
                    return href;
            }
        }
        return null;
    }

    private void parseNavXhtml(String html, Path baseDir) {
        // Find <nav epub:type="toc"> block
        int navStart = html.indexOf("epub:type=\"toc\"");
        if (navStart < 0)
            navStart = html.indexOf("epub:type='toc'");
        if (navStart < 0)
            return;

        int olStart = html.indexOf("<ol", navStart);
        if (olStart < 0)
            return;

        // Find matching closing </ol>
        int depth = 0, pos = olStart;
        int olEnd = -1;
        while (pos < html.length()) {
            if (html.startsWith("<ol", pos)) {
                depth++;
                pos += 3;
            } else if (html.startsWith("</ol>", pos)) {
                depth--;
                if (depth == 0) {
                    olEnd = pos + 5;
                    break;
                }
                pos += 5;
            } else {
                pos++;
            }
        }
        if (olEnd < 0)
            return;

        String olBlock = html.substring(olStart, olEnd);
        parseNavOl(olBlock, baseDir, toc, new int[] { 0 });
    }

    private void parseNavOl(String ol, Path baseDir,
            List<TocEntry> target, int[] counter) {
        String[] liParts = ol.split("<li[^>]*>");
        for (String li : liParts) {
            if (li.isBlank())
                continue;
            String href = attr(li, "href");
            String label = stripTags(li.split("</a>")[0]).trim();
            if (label.isBlank())
                continue;

            String resolved = "";
            if (href != null && !href.isEmpty()) {
                String bareHref = href.split("#")[0];
                Path absPath = baseDir.resolve(bareHref).normalize();
                if (Files.exists(absPath)) {
                    resolved = absPath.toFile().toURI().toString();
                    if (href.contains("#"))
                        resolved += "#" + href.split("#")[1];
                }
            }

            TocEntry entry = new TocEntry(label, href != null ? href : "",
                    resolved, counter[0]++);
            target.add(entry);

            // Nested <ol>
            int nestedOl = li.indexOf("<ol");
            if (nestedOl >= 0) {
                parseNavOl(li.substring(nestedOl), baseDir,
                        entry.children, counter);
            }
        }
    }

    private void parseNcx(String ncx, Path baseDir) {
        String[] points = ncx.split("<navPoint ");
        int order = 0;
        for (String pt : points) {
            if (!pt.contains("<navLabel>") && !pt.contains("<text>"))
                continue;
            String label = tagValue(pt, "text", "");
            String src = attr(pt.contains("<content") ? pt.substring(pt.indexOf("<content")) : "", "src");
            if (label.isBlank())
                continue;

            String resolved = "";
            if (src != null && !src.isEmpty()) {
                String bareSrc = src.split("#")[0];
                Path absPath = baseDir.resolve(bareSrc).normalize();
                if (Files.exists(absPath)) {
                    resolved = absPath.toFile().toURI().toString();
                    if (src.contains("#"))
                        resolved += "#" + src.split("#")[1];
                }
            }
            toc.add(new TocEntry(label, src != null ? src : "",
                    resolved, order++));
        }
    }

    // ── Theme / CSS injection ─────────────────────────────────────────────────

    public static class ReaderTheme {
        public String bgColor = "#0D0D0D";
        public String textColor = "#E8E8E8";
        public String fontFamily = "Georgia, 'Times New Roman', serif";
        public double fontSize = 18.0; // px
        public double lineHeight = 1.8;
        public double wordSpacing = 0.0; // em
        public double paragraphSpace = 1.0; // em (margin-bottom on p)
        public String textAlign = "justify";
        public double marginH = 48.0; // px left+right margin per column
    }

    private String buildReaderCss(ReaderTheme t) {
        return String.format("""
                html, body {
                    background-color: %s !important;
                    color: %s !important;
                    font-family: %s !important;
                    font-size: %.1fpx !important;
                    line-height: %.2f !important;
                    word-spacing: %.2fem !important;
                    margin: 0 !important;
                    padding: 0 %.0fpx !important;
                    -webkit-text-size-adjust: none !important;
                }
                p, div, span, li, td, th, blockquote {
                    color: %s !important;
                    text-align: %s !important;
                    margin-bottom: %.2fem !important;
                }
                a { color: #C0284A !important; text-decoration: none !important; }
                img { max-width: 100%% !important; height: auto !important; display: block;
                      margin: 1em auto !important; }
                h1,h2,h3,h4,h5,h6 {
                    color: %s !important;
                    margin-top: 1.2em !important;
                    margin-bottom: 0.4em !important;
                }
                """,
                t.bgColor, t.textColor, t.fontFamily, t.fontSize,
                t.lineHeight, t.wordSpacing, t.marginH,
                t.textColor, t.textAlign, t.paragraphSpace,
                t.textColor);
    }

    // ── XML/HTML helpers ──────────────────────────────────────────────────────

    private String readFile(Path p) throws IOException {
        byte[] bytes = Files.readAllBytes(p);
        // Try UTF-8, fall back to ISO-8859-1
        try {
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    private String tagValue(String xml, String tag, String def) {
        int s = xml.indexOf('<' + tag);
        if (s < 0)
            return def;
        int gt = xml.indexOf('>', s);
        if (gt < 0)
            return def;
        int e = xml.indexOf("</" + tag, gt);
        if (e < 0)
            return def;
        String v = xml.substring(gt + 1, e).trim();
        return v.isEmpty() ? def : v;
    }

    private String attr(String tag, String name) {
        for (String q : new String[] { "\"", "'" }) {
            String search = name + "=" + q;
            int idx = tag.indexOf(search);
            if (idx < 0)
                continue;
            int start = idx + search.length();
            int end = tag.indexOf(q, start);
            if (end > start)
                return tag.substring(start, end);
        }
        return null;
    }

    private String block(String xml, String startTag, String endTag) {
        int s = xml.indexOf(startTag);
        int e = xml.indexOf(endTag, s);
        if (s < 0 || e < 0)
            return null;
        return xml.substring(s, e + endTag.length());
    }

    private String stripTags(String html) {
        return html.replaceAll("<[^>]+>", "").trim();
    }

    private String titleFromHref(String href) {
        String name = href.contains("/") ? href.substring(href.lastIndexOf('/') + 1) : href;
        if (name.contains("."))
            name = name.substring(0, name.lastIndexOf('.'));
        return name.replace('-', ' ').replace('_', ' ');
    }

    private void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir))
            return;
        Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
    }
}