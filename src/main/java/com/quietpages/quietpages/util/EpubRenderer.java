package com.quietpages.quietpages.util;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extracts an EPUB ZIP to a temp directory and provides:
 * - Ordered spine (chapter list) with file:// URLs for WebView
 * - Table of contents tree
 * - CSS-injected chapter HTML (typography only; layout/pagination handled by
 * JS)
 */
public class EpubRenderer {

    // ── Data classes ──────────────────────────────────────────────────────────

    public static class Chapter {
        public final String id;
        public final String title;
        public final String filePath;
        public final String fileUrl;

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
        public final String src;
        public final String resolvedUrl;
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

    public static class ReaderTheme {
        public String bgColor = "#0D0D0D";
        public String textColor = "#E8E8E8";
        public String fontFamily = "Georgia, 'Times New Roman', serif";
        public double fontSize = 18.0;
        public double lineHeight = 1.8;
        public double wordSpacing = 0.0;
        public double paragraphSpace = 1.0;
        public String textAlign = "justify";
        public double marginH = 40.0;
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    private final File epubFile;
    private Path extractDir;
    private String opfDir = "";
    private final List<Chapter> spine = new ArrayList<>();
    private final List<TocEntry> toc = new ArrayList<>();
    private String bookTitle = "";
    private boolean loaded = false;

    // ── Constructor / API ─────────────────────────────────────────────────────

    public EpubRenderer(File epubFile) {
        this.epubFile = epubFile;
    }

    public void load() throws IOException {
        if (loaded)
            return;

        String safeName = epubFile.getName().replaceAll("[^a-zA-Z0-9._-]", "_");
        extractDir = Path.of(System.getProperty("java.io.tmpdir"), "QuietPages_reader", safeName);
        Files.createDirectories(extractDir);

        try (ZipFile zip = new ZipFile(epubFile)) {
            for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements();) {
                ZipEntry entry = en.nextElement();
                Path dest = extractDir.resolve(entry.getName()).normalize();
                if (!dest.startsWith(extractDir))
                    continue;
                if (entry.isDirectory()) {
                    Files.createDirectories(dest);
                    continue;
                }
                Files.createDirectories(dest.getParent());
                try (InputStream is = zip.getInputStream(entry)) {
                    Files.write(dest, is.readAllBytes(),
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                }
            }
        }

        String opfPath = findOpfPath();
        if (opfPath != null) {
            opfDir = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
            String opf = readFile(extractDir.resolve(opfPath));
            parseOpf(opf);
            parseToc(opf);
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

    public int getTotalChapters() {
        return spine.size();
    }

    public String getChapterUrl(int i) {
        if (i < 0 || i >= spine.size())
            return null;
        return spine.get(i).fileUrl;
    }

    /**
     * Returns a file:// URL for a CSS-injected copy of the chapter HTML.
     * The copy lives in the SAME directory as the source so relative image/CSS
     * paths resolve correctly when WebView loads it.
     */
    public String getChapterUrlWithTheme(int i, ReaderTheme t) throws IOException {
        if (i < 0 || i >= spine.size())
            return null;
        Chapter ch = spine.get(i);
        Path orig = Path.of(ch.filePath);
        String html = readFile(orig);

        // Remove any previous QP injection
        html = html.replaceAll(
                "(?s)<style[^>]+id=['\"]qp-reader-style['\"][^>]*>.*?</style>", "");

        String css = buildTypographyCss(t);
        String injection = "<style id=\"qp-reader-style\">\n" + css + "\n</style>";

        if (html.contains("</head>"))
            html = html.replace("</head>", injection + "\n</head>");
        else if (html.contains("<head>"))
            html = html.replace("<head>", "<head>\n" + injection);
        else
            html = injection + "\n" + html;

        // Write into same folder so relative URLs still resolve
        Path themed = orig.getParent().resolve("_qp_" + i + ".html");
        Files.writeString(themed, html, StandardCharsets.UTF_8);
        return themed.toFile().toURI().toString();
    }

    public int findSpineIndex(String fileUrl) {
        if (fileUrl == null)
            return 0;
        // Strip _qp_N.html if querying a themed copy
        String base = fileUrl.split("#")[0].replaceAll("_qp_\\d+\\.html$", "");
        for (int i = 0; i < spine.size(); i++) {
            String sb = spine.get(i).fileUrl.split("#")[0];
            if (sb.equals(base) || sb.contains(base) || base.contains(sb))
                return i;
        }
        return 0;
    }

    public void cleanup() {
        if (extractDir != null) {
            try {
                deleteDir(extractDir);
            } catch (IOException ignored) {
            }
        }
    }

    // ── OPF parsing ───────────────────────────────────────────────────────────

    private String findOpfPath() throws IOException {
        Path c = extractDir.resolve("META-INF/container.xml");
        if (!Files.exists(c))
            return null;
        String xml = readFile(c);
        int i = xml.indexOf("full-path=\"");
        if (i < 0)
            return null;
        int s = i + 11, e = xml.indexOf('"', s);
        return e > s ? xml.substring(s, e) : null;
    }

    private void parseOpf(String opf) {
        bookTitle = tagVal(opf, "dc:title", epubFile.getName());

        Map<String, String> idHref = new LinkedHashMap<>();
        Map<String, String> idMime = new HashMap<>();
        String manifest = block(opf, "<manifest", "</manifest>");
        if (manifest != null) {
            for (String item : manifest.split("<item ")) {
                String id = attr(item, "id"), href = attr(item, "href"), mt = attr(item, "media-type");
                if (id != null && href != null) {
                    idHref.put(id, href);
                    if (mt != null)
                        idMime.put(id, mt);
                }
            }
        }

        String spineBlock = block(opf, "<spine", "</spine>");
        if (spineBlock != null) {
            for (String item : spineBlock.split("<itemref ")) {
                String idref = attr(item, "idref");
                if (idref == null)
                    continue;
                String href = idHref.get(idref);
                if (href == null)
                    continue;
                String mt = idMime.getOrDefault(idref, "");
                String lh = href.toLowerCase();
                if (!mt.contains("html") && !mt.contains("xhtml")
                        && !lh.endsWith(".html") && !lh.endsWith(".xhtml") && !lh.endsWith(".htm"))
                    continue;
                String decoded = href;
                try {
                    decoded = java.net.URLDecoder.decode(href, StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                }
                Path abs = extractDir.resolve(opfDir + decoded).normalize();
                if (Files.exists(abs))
                    spine.add(new Chapter(idref, nameFromHref(href), abs.toString()));
            }
        }
    }

    private void parseToc(String opf) {
        String navHref = findNavHref(opf);
        if (navHref != null) {
            Path navPath = extractDir.resolve(opfDir + navHref).normalize();
            if (Files.exists(navPath)) {
                try {
                    parseNavXhtml(readFile(navPath), navPath.getParent());
                } catch (IOException ignored) {
                }
                if (!toc.isEmpty())
                    return;
            }
        }
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
        if (toc.isEmpty()) {
            for (int i = 0; i < spine.size(); i++)
                toc.add(new TocEntry(spine.get(i).title, spine.get(i).filePath, spine.get(i).fileUrl, i));
        }
    }

    private String findNavHref(String opf) {
        String m = block(opf, "<manifest", "</manifest>");
        if (m == null)
            return null;
        for (String item : m.split("<item ")) {
            String p = attr(item, "properties");
            if (p != null && p.contains("nav"))
                return attr(item, "href");
        }
        return null;
    }

    private String findNcxHref(String opf) {
        String m = block(opf, "<manifest", "</manifest>");
        if (m == null)
            return null;
        for (String item : m.split("<item ")) {
            String mt = attr(item, "media-type");
            if (mt != null && mt.contains("ncx"))
                return attr(item, "href");
        }
        if (m != null) {
            for (String item : m.split("<item ")) {
                String h = attr(item, "href");
                if (h != null && h.endsWith(".ncx"))
                    return h;
            }
        }
        return null;
    }

    private void parseNavXhtml(String html, Path base) {
        int navStart = html.indexOf("epub:type=\"toc\"");
        if (navStart < 0)
            navStart = html.indexOf("epub:type='toc'");
        if (navStart < 0)
            navStart = html.indexOf("<nav");
        if (navStart < 0)
            return;
        int olStart = html.indexOf("<ol", navStart);
        if (olStart < 0)
            return;
        int depth = 0, pos = olStart, olEnd = -1;
        while (pos < html.length()) {
            if (html.startsWith("<ol", pos)) {
                depth++;
                pos += 3;
            } else if (html.startsWith("</ol>", pos)) {
                if (--depth == 0) {
                    olEnd = pos + 5;
                    break;
                }
                pos += 5;
            } else
                pos++;
        }
        if (olEnd < 0)
            return;
        parseNavOl(html.substring(olStart, olEnd), base, toc, new int[] { 0 });
    }

    private void parseNavOl(String ol, Path base, List<TocEntry> target, int[] ctr) {
        for (String li : ol.split("<li[^>]*>")) {
            if (li.isBlank())
                continue;
            String href = attr(li, "href");
            String label = stripTags(li.contains("</a>") ? li.split("</a>")[0] : li).trim();
            if (label.isBlank())
                continue;
            String resolved = "";
            if (href != null && !href.isEmpty()) {
                String bare = href.split("#")[0];
                try {
                    bare = java.net.URLDecoder.decode(bare, StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                }
                Path abs = base.resolve(bare).normalize();
                if (Files.exists(abs)) {
                    resolved = abs.toFile().toURI().toString();
                    if (href.contains("#"))
                        resolved += "#" + href.split("#")[1];
                }
            }
            TocEntry entry = new TocEntry(label, href != null ? href : "", resolved, ctr[0]++);
            target.add(entry);
            int nested = li.indexOf("<ol");
            if (nested >= 0)
                parseNavOl(li.substring(nested), base, entry.children, ctr);
        }
    }

    private void parseNcx(String ncx, Path base) {
        int order = 0;
        for (String pt : ncx.split("<navPoint ")) {
            if (!pt.contains("<text>"))
                continue;
            String label = tagVal(pt, "text", "");
            String src = attr(pt.contains("<content") ? pt.substring(pt.indexOf("<content")) : "", "src");
            if (label.isBlank())
                continue;
            String resolved = "";
            if (src != null && !src.isEmpty()) {
                String bare = src.split("#")[0];
                try {
                    bare = java.net.URLDecoder.decode(bare, StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                }
                Path abs = base.resolve(bare).normalize();
                if (Files.exists(abs)) {
                    resolved = abs.toFile().toURI().toString();
                    if (src.contains("#"))
                        resolved += "#" + src.split("#")[1];
                }
            }
            toc.add(new TocEntry(label, src != null ? src : "", resolved, order++));
        }
    }

    // ── CSS ───────────────────────────────────────────────────────────────────

    /**
     * Typography-only CSS.
     * DOES NOT set: width, height, overflow, column-count, padding, margin on
     * body/html.
     * Those are all controlled by the JS pagination script in ReaderController
     * after the WebView has been physically measured.
     */
    private String buildTypographyCss(ReaderTheme t) {
        return String.format("""
                /* ── QP Reader — typography only ─────────────── */
                * {
                    box-sizing: border-box !important;
                    -webkit-text-size-adjust: none !important;
                }
                html, body {
                    background-color: %s !important;
                }
                body {
                    color:        %s !important;
                    font-family:  %s !important;
                    font-size:    %.1fpx !important;
                    line-height:  %.2f !important;
                    word-spacing: %.3fem !important;
                }
                p {
                    color:         %s !important;
                    text-align:    %s !important;
                    margin-top:    0 !important;
                    margin-bottom: %.2fem !important;
                    orphans: 3 !important;
                    widows:  3 !important;
                }
                span, li, td, th, div, section, article {
                    color: %s !important;
                }
                a { color: #C0284A !important; text-decoration: none !important; }

                /* ── Images: contained, never split across column boundary ── */
                img {
                    display:    block !important;
                    max-width:  100%% !important;
                    max-height: 80vh !important;
                    width:      auto !important;
                    height:     auto !important;
                    object-fit: contain !important;
                    margin:     1em auto !important;
                    -webkit-column-break-inside: avoid !important;
                    break-inside:            avoid !important;
                    page-break-inside:       avoid !important;
                }
                figure {
                    margin: 0.8em 0 !important;
                    text-align: center !important;
                    -webkit-column-break-inside: avoid !important;
                    break-inside:            avoid !important;
                }
                figcaption {
                    font-size:  0.82em !important;
                    color:      #888 !important;
                    text-align: center !important;
                    margin-top: 0.3em !important;
                }
                svg {
                    max-width:  100%% !important;
                    max-height: 80vh !important;
                    -webkit-column-break-inside: avoid !important;
                    break-inside: avoid !important;
                }

                /* ── Headings ────────────────────────────────── */
                h1, h2, h3, h4, h5, h6 {
                    color:       %s !important;
                    font-family: %s !important;
                    line-height: 1.25 !important;
                    margin-top:    1.2em !important;
                    margin-bottom: 0.4em !important;
                    -webkit-column-break-after: avoid !important;
                    break-after: avoid !important;
                }
                h1 { font-size: 1.55em !important; }
                h2 { font-size: 1.28em !important; }
                h3 { font-size: 1.10em !important; }

                /* ── Block elements that must not split ───────── */
                table, pre, blockquote {
                    -webkit-column-break-inside: avoid !important;
                    break-inside: avoid !important;
                }
                blockquote {
                    border-left:  3px solid #C0284A !important;
                    margin-left:  0 !important;
                    padding-left: 1em !important;
                    color:        #AAAAAA !important;
                    font-style:   italic !important;
                }
                pre, code {
                    background:    rgba(255,255,255,0.06) !important;
                    border-radius: 3px !important;
                    padding:       0.15em 0.4em !important;
                    font-size:     0.87em !important;
                }
                hr {
                    border:     none !important;
                    border-top: 1px solid rgba(255,255,255,0.08) !important;
                    margin:     2em auto !important;
                    width:      35%% !important;
                }
                table { width: 100%% !important; border-collapse: collapse !important; }
                td, th {
                    border:  1px solid rgba(255,255,255,0.1) !important;
                    padding: 0.35em 0.6em !important;
                    color:   %s !important;
                }
                """,
                t.bgColor, // html,body bg
                t.textColor, t.fontFamily, t.fontSize, // body
                t.lineHeight, t.wordSpacing,
                t.textColor, t.textAlign, t.paragraphSpace, // p
                t.textColor, // span etc
                t.textColor, t.fontFamily, // headings
                t.textColor); // td
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String readFile(Path p) throws IOException {
        byte[] bytes = Files.readAllBytes(p);
        String preview = new String(bytes, 0, Math.min(600, bytes.length), StandardCharsets.UTF_8);
        if (preview.contains("charset=\"iso-8859") || preview.contains("charset=iso-8859"))
            return new String(bytes, StandardCharsets.ISO_8859_1);
        if (preview.contains("charset=\"windows-1252") || preview.contains("charset=windows-1252"))
            return new String(bytes, "windows-1252");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private String tagVal(String xml, String tag, String def) {
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
            int s = idx + search.length(), e = tag.indexOf(q, s);
            if (e > s)
                return tag.substring(s, e);
        }
        return null;
    }

    private String block(String xml, String start, String end) {
        int s = xml.indexOf(start), e = xml.indexOf(end, s);
        if (s < 0 || e < 0)
            return null;
        return xml.substring(s, e + end.length());
    }

    private String stripTags(String html) {
        return html.replaceAll("<[^>]+>", "").replaceAll("&[a-zA-Z#\\d]+;", " ").trim();
    }

    private String nameFromHref(String href) {
        String n = href.contains("/") ? href.substring(href.lastIndexOf('/') + 1) : href;
        if (n.contains("."))
            n = n.substring(0, n.lastIndexOf('.'));
        return n.replace('-', ' ').replace('_', ' ');
    }

    private void deleteDir(Path dir) throws IOException {
        if (!Files.exists(dir))
            return;
        Files.walk(dir).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    }
}