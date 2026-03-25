package com.quietpages.quietpages.util;

import com.quietpages.quietpages.model.Book;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads an EPUB file (which is a ZIP) and extracts:
 *  - Title, author, publisher, language, description, series from OPF metadata
 *  - Cover image bytes
 *  - Approximate word/line count from all XHTML content files
 *
 * No third-party EPUB library needed — uses only java.util.zip.
 */
public class EpubImporter {

    /**
     * Imports an EPUB file and returns a populated Book (not yet persisted).
     * Falls back gracefully if any metadata is missing.
     */
    public static Book importEpub(File file) throws IOException {
        Book book = new Book();
        book.setFilePath(file.getAbsolutePath());
        book.setFileType("epub");
        book.setDateAdded(LocalDateTime.now());

        // Default title = filename without extension
        String fileName = file.getName();
        String baseName = fileName.endsWith(".epub")
                ? fileName.substring(0, fileName.length() - 5) : fileName;
        book.setTitle(baseName);

        try (ZipFile zip = new ZipFile(file)) {

            // 1. Find the OPF file path from META-INF/container.xml
            String opfPath = findOpfPath(zip);

            if (opfPath != null) {
                String opfContent = readEntry(zip, opfPath);
                if (opfContent != null) {
                    parseOpf(opfContent, book);

                    // 2. Extract cover image
                    byte[] cover = extractCover(zip, opfPath, opfContent);
                    if (cover != null) book.setCoverImageData(cover);

                    // 3. Count words and lines across all content files
                    int[] counts = countWordsAndLines(zip, opfPath, opfContent);
                    book.setWordCount(counts[0]);
                    book.setLineCount(counts[1]);
                }
            }
        }

        // Ensure non-null title/author
        if (book.getTitle() == null || book.getTitle().isBlank()) book.setTitle(baseName);
        if (book.getAuthor() == null || book.getAuthor().isBlank()) book.setAuthor("Unknown Author");

        return book;
    }

    // ── OPF path from container.xml ───────────────────────────────────────────
    private static String findOpfPath(ZipFile zip) throws IOException {
        String container = readEntry(zip, "META-INF/container.xml");
        if (container == null) return null;
        // <rootfile full-path="OEBPS/content.opf" .../>
        int idx = container.indexOf("full-path=\"");
        if (idx < 0) return null;
        int start = idx + 11;
        int end = container.indexOf('"', start);
        return end > start ? container.substring(start, end) : null;
    }

    // ── Parse OPF for metadata ─────────────────────────────────────────────────
    private static void parseOpf(String opf, Book book) {
        book.setTitle(   getTagValue(opf, "dc:title",       book.getTitle()));
        book.setAuthor(  getTagValue(opf, "dc:creator",     "Unknown Author"));
        book.setPublisher(getTagValue(opf, "dc:publisher",  ""));
        book.setLanguage(getTagValue(opf, "dc:language",    "en"));
        book.setDescription(getTagValue(opf, "dc:description", ""));
        book.setGenre(   getTagValue(opf, "dc:subject",     ""));

        // Calibre series meta: <meta name="calibre:series" content="..."/>
        book.setSeries(  getMetaContent(opf, "calibre:series"));
        String seriesIdx = getMetaContent(opf, "calibre:series_index");
        if (!seriesIdx.isEmpty()) {
            try { book.setSeriesNumber((int) Double.parseDouble(seriesIdx)); }
            catch (NumberFormatException ignored) {}
        }
    }

    // ── Cover extraction ──────────────────────────────────────────────────────
    private static byte[] extractCover(ZipFile zip, String opfPath, String opf) throws IOException {
        // Strategy 1: <meta name="cover" content="cover-id"/>
        String coverId = getMetaContent(opf, "cover");
        if (!coverId.isEmpty()) {
            String href = getItemHrefById(opf, coverId);
            if (href != null) {
                byte[] data = readEntryBytes(zip, resolveRelative(opfPath, href));
                if (data != null) return data;
            }
        }

        // Strategy 2: item with id containing "cover" or properties="cover-image"
        String href = getItemHrefByProperty(opf, "cover-image");
        if (href != null) {
            byte[] data = readEntryBytes(zip, resolveRelative(opfPath, href));
            if (data != null) return data;
        }

        return null;
    }

    // ── Word / line count ─────────────────────────────────────────────────────
    private static int[] countWordsAndLines(ZipFile zip, String opfPath, String opf) {
        int words = 0, lines = 0;
        // Iterate through manifest items that are xhtml/html
        String manifest = extractBlock(opf, "<manifest", "</manifest>");
        if (manifest == null) return new int[]{0, 0};

        String[] items = manifest.split("<item ");
        for (String item : items) {
            String mediaType = attr(item, "media-type");
            if (mediaType == null) continue;
            if (!mediaType.contains("html") && !mediaType.contains("xml")) continue;
            String href = attr(item, "href");
            if (href == null) continue;
            try {
                String content = readEntry(zip, resolveRelative(opfPath, href));
                if (content != null) {
                    String text = stripTags(content);
                    words += text.split("\\s+").length;
                    lines += text.split("\n").length;
                }
            } catch (Exception ignored) {}
        }
        return new int[]{words, lines};
    }

    // ── ZIP helpers ───────────────────────────────────────────────────────────
    private static String readEntry(ZipFile zip, String path) throws IOException {
        byte[] bytes = readEntryBytes(zip, path);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] readEntryBytes(ZipFile zip, String path) throws IOException {
        if (path == null) return null;
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) {
            // Try case-insensitive fallback
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.getName().equalsIgnoreCase(path)) { entry = e; break; }
            }
        }
        if (entry == null) return null;
        try (InputStream is = zip.getInputStream(entry)) {
            return is.readAllBytes();
        }
    }

    // ── XML helpers (no DOM, just string search) ──────────────────────────────
    private static String getTagValue(String xml, String tag, String defaultVal) {
        // Handles <dc:title>...</ and <dc:title opf:...>...</
        int start = xml.indexOf('<' + tag);
        if (start < 0) return defaultVal;
        int gt = xml.indexOf('>', start);
        if (gt < 0) return defaultVal;
        int end = xml.indexOf("</" + tag, gt);
        if (end < 0) return defaultVal;
        String val = xml.substring(gt + 1, end).trim();
        return val.isEmpty() ? defaultVal : val;
    }

    private static String getMetaContent(String opf, String name) {
        // <meta name="cover" content="cover-image"/>  OR  <meta name="calibre:series" content="..."/>
        int idx = opf.indexOf("name=\"" + name + "\"");
        if (idx < 0) return "";
        int contentIdx = opf.indexOf("content=\"", idx);
        if (contentIdx < 0) return "";
        int start = contentIdx + 9;
        int end = opf.indexOf('"', start);
        return end > start ? opf.substring(start, end) : "";
    }

    private static String getItemHrefById(String opf, String id) {
        // <item id="cover-id" href="images/cover.jpg" .../>
        String search = "id=\"" + id + "\"";
        int idx = opf.indexOf(search);
        if (idx < 0) return null;
        int lineStart = opf.lastIndexOf('<', idx);
        int lineEnd   = opf.indexOf('>', idx);
        if (lineStart < 0 || lineEnd < 0) return null;
        return attr(opf.substring(lineStart, lineEnd), "href");
    }

    private static String getItemHrefByProperty(String opf, String property) {
        String search = "properties=\"" + property + "\"";
        int idx = opf.indexOf(search);
        if (idx < 0) return null;
        int lineStart = opf.lastIndexOf('<', idx);
        int lineEnd   = opf.indexOf('>', idx);
        if (lineStart < 0 || lineEnd < 0) return null;
        return attr(opf.substring(lineStart, lineEnd), "href");
    }

    private static String attr(String tag, String name) {
        String search = name + "=\"";
        int idx = tag.indexOf(search);
        if (idx < 0) return null;
        int start = idx + search.length();
        int end = tag.indexOf('"', start);
        return end > start ? tag.substring(start, end) : null;
    }

    private static String extractBlock(String xml, String startTag, String endTag) {
        int s = xml.indexOf(startTag);
        int e = xml.indexOf(endTag, s);
        if (s < 0 || e < 0) return null;
        return xml.substring(s, e + endTag.length());
    }

    /** Resolve a relative href against an OPF path like OEBPS/content.opf */
    private static String resolveRelative(String opfPath, String href) {
        if (href.startsWith("/")) return href.substring(1);
        int slash = opfPath.lastIndexOf('/');
        if (slash < 0) return href;
        return opfPath.substring(0, slash + 1) + href;
    }

    private static String stripTags(String html) {
        return html.replaceAll("<[^>]+>", " ").replaceAll("&[a-z]+;", " ");
    }
}