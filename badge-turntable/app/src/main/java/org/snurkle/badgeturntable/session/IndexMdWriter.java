package org.snurkle.badgeturntable.session;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes a badge's index.md.
 *
 * A new badge starts from the repo's _template/index.md (bundled into the app's
 * assets at build time): its identity keys are filled in and `images: []` becomes
 * the captured list, with a `videos:` block added after it.
 *
 * An existing index.md (possibly edited on the PC since) is changed as little as
 * possible: identity keys the app owns are updated, existing image/video entries are
 * kept exactly as they are, the app's new files are appended, and files the user
 * deleted in the app are removed. PC edits to captions, order and highlight win.
 */
public final class IndexMdWriter {

    private static final Pattern TOP_KEY = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*):(.*)$");

    private IndexMdWriter() {}

    public static String write(String existingOrTemplate, Session s) {
        List<String> lines = new ArrayList<>(List.of(existingOrTemplate.replace("\r\n", "\n").split("\n", -1)));

        setScalar(lines, "title", quote(s.title));
        setScalar(lines, "slug", s.effectiveSlug());
        setScalar(lines, "badge_id", quote(s.badgeId == null ? "" : s.badgeId));
        if (s.year > 0) setScalar(lines, "year", String.valueOf(s.year));
        setScalar(lines, "con", s.con);
        setScalar(lines, "event", quote(s.event == null ? "" : s.event));
        setScalar(lines, "group", quote(s.group == null ? "" : s.group));

        mergeList(lines, "images", s, Session.Media.Kind.IMAGE, null);
        mergeList(lines, "videos", s, Session.Media.Kind.VIDEO, "images");
        return String.join("\n", lines);
    }

    // ---- front matter helpers ------------------------------------------------

    /** Index of the closing "---" of the front matter. */
    private static int frontMatterEnd(List<String> lines) {
        if (lines.isEmpty() || !lines.get(0).trim().equals("---")) throw new IllegalArgumentException("no front matter");
        for (int i = 1; i < lines.size(); i++) if (lines.get(i).trim().equals("---")) return i;
        throw new IllegalArgumentException("unterminated front matter");
    }

    private static int findKey(List<String> lines, String key) {
        int end = frontMatterEnd(lines);
        for (int i = 1; i < end; i++) {
            Matcher m = TOP_KEY.matcher(lines.get(i));
            if (m.matches() && m.group(1).equals(key)) return i;
        }
        return -1;
    }

    /** Replaces "key: ..." (keeping any trailing comment), or adds it before the closing ---. */
    private static void setScalar(List<String> lines, String key, String value) {
        int i = findKey(lines, key);
        if (i < 0) {
            lines.add(frontMatterEnd(lines), key + ": " + value);
        } else {
            lines.set(i, key + ": " + value);
        }
    }

    /** Lines after a list key that belong to it: indented lines or "- " items. */
    private static int blockEnd(List<String> lines, int keyLine) {
        int end = frontMatterEnd(lines);
        int i = keyLine + 1;
        while (i < end) {
            String l = lines.get(i);
            if (l.startsWith(" ") || l.startsWith("\t") || l.startsWith("-")) {
                i++;
            } else {
                break;
            }
        }
        return i;
    }

    private static void mergeList(List<String> lines, String key, Session s, Session.Media.Kind kind, String after) {
        List<Session.Media> ours = new ArrayList<>();
        for (Session.Media m : s.media) if (m.kind == kind) ours.add(m);

        int keyLine = findKey(lines, key);
        List<List<String>> items = new ArrayList<>();
        if (keyLine >= 0) {
            items = parseItems(lines, keyLine);
            int end = blockEnd(lines, keyLine);
            for (int i = end - 1; i >= keyLine; i--) lines.remove(i);
        } else {
            if (ours.isEmpty()) return;
            int anchor = after == null ? -1 : findKey(lines, after);
            keyLine = anchor < 0 ? frontMatterEnd(lines) : blockEnd(lines, anchor);
        }

        Set<String> existing = new LinkedHashSet<>();
        boolean anyHighlight = false;
        List<List<String>> kept = new ArrayList<>();
        for (List<String> item : items) {
            String f = itemFilename(item);
            if (f != null && s.removed.contains(f)) continue;
            if (f != null) existing.add(f);
            if (String.join("\n", item).matches("(?s).*\\bhighlight:\\s*true\\b.*")) anyHighlight = true;
            kept.add(item);
        }
        for (Session.Media m : ours) {
            if (existing.contains(m.filename)) continue;
            List<String> item = new ArrayList<>();
            item.add("  - filename: " + quote(m.filename));
            item.add("    caption: " + quote(m.caption == null ? "" : m.caption));
            // The front photo leads: it's the card thumbnail and the gallery's main image.
            if (kind == Session.Media.Kind.IMAGE && !anyHighlight && m.filename.equals("front.jpg")) {
                item.add("    highlight: true");
                anyHighlight = true;
            }
            kept.add(item);
        }

        List<String> block = new ArrayList<>();
        if (kept.isEmpty()) {
            block.add(key + ": []");
        } else {
            block.add(key + ":");
            for (List<String> item : kept) block.addAll(item);
        }
        lines.addAll(keyLine, block);
    }

    /** Splits a list block into items, each starting at its "- " line. Handles "key: []". */
    private static List<List<String>> parseItems(List<String> lines, int keyLine) {
        List<List<String>> items = new ArrayList<>();
        int end = blockEnd(lines, keyLine);
        List<String> cur = null;
        for (int i = keyLine + 1; i < end; i++) {
            String l = lines.get(i);
            if (l.trim().startsWith("- ")) {
                cur = new ArrayList<>();
                items.add(cur);
            }
            if (cur != null) cur.add(l);
        }
        return items;
    }

    private static final Pattern FILENAME = Pattern.compile("filename:\\s*\"?([^\"#]+?)\"?\\s*$");

    private static String itemFilename(List<String> item) {
        for (String l : item) {
            Matcher m = FILENAME.matcher(l.trim().replaceFirst("^- ", ""));
            if (m.find()) return m.group(1).trim();
        }
        return null;
    }

    static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
