package org.snurkle.badgeturntable.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class IndexMdWriterTest {

    private static final String TEMPLATE = String.join("\n",
            "---",
            "layout: badge",
            "",
            "# Display name of the badge",
            "title: \"Badge Name Here\"",
            "slug: badge-slug-here",
            "badge_id: \"\"",
            "year: 2025",
            "con: defcon",
            "event: \"DEF CON 33\"",
            "group: \"\"",
            "images: []",
            "# images:",
            "#   - filename: front.jpg",
            "status: stub",
            "---",
            "",
            "Add notes here.",
            "");

    private static Session mk9() {
        Session s = new Session();
        s.title = "Mk9";
        s.group = "dczia";
        s.year = 2026;
        s.con = "defcon";
        s.event = "DEF CON 34";
        s.badgeId = "00E";
        s.media.add(new Session.Media("front.jpg", "Front", Session.Media.Kind.IMAGE));
        s.media.add(new Session.Media("side-090.jpg", "Side (90°)", Session.Media.Kind.IMAGE));
        s.media.add(new Session.Media("spin.mp4", "", Session.Media.Kind.VIDEO));
        return s;
    }

    @Test
    public void fillsTemplate() {
        String out = IndexMdWriter.write(TEMPLATE, mk9());
        assertTrue(out, out.contains("title: \"Mk9\"\n"));
        assertTrue(out.contains("slug: dczia-mk9-2026\n"));
        assertTrue(out.contains("badge_id: \"00E\"\n"));
        assertTrue(out.contains("year: 2026\n"));
        assertTrue(out.contains("event: \"DEF CON 34\"\n"));
        assertTrue(out.contains("images:\n  - filename: \"front.jpg\"\n    caption: \"Front\"\n    highlight: true\n"
                + "  - filename: \"side-090.jpg\"\n    caption: \"Side (90°)\"\n"
                + "videos:\n  - filename: \"spin.mp4\"\n    caption: \"\"\n# images:\n"));
        assertTrue("template comments kept", out.contains("# Display name of the badge"));
        assertTrue("body kept", out.endsWith("Add notes here.\n"));
        assertFalse(out.contains("highlight: true\n    highlight"));
    }

    @Test
    public void keepsPcEditsWhenAppending() {
        String first = IndexMdWriter.write(TEMPLATE, mk9());
        // On the PC: caption edited, highlight moved to the side view.
        String edited = first
                .replace("caption: \"Front\"\n    highlight: true", "caption: \"Front, lights on\"")
                .replace("caption: \"Side (90°)\"", "caption: \"Side\"\n    highlight: true");

        Session s = mk9();
        s.media.add(new Session.Media("lanyard.jpg", "Lanyard", Session.Media.Kind.IMAGE));
        s.remove("side-090.jpg");
        String out = IndexMdWriter.write(edited, s);

        assertTrue(out, out.contains("caption: \"Front, lights on\""));
        assertFalse("removed file dropped", out.contains("side-090.jpg"));
        assertTrue(out.contains("  - filename: \"lanyard.jpg\"\n    caption: \"Lanyard\"\n"));
        assertFalse("no second highlight added", out.contains("front.jpg\"\n    caption: \"Front, lights on\"\n    highlight"));
        assertEquals("one videos block", out.indexOf("videos:"), out.lastIndexOf("videos:"));
    }

    @Test
    public void realTemplate() throws Exception {
        File f = new File("../../../_template/index.md");  // tests run from app/
        assumeTrue("repo template not found", f.exists());
        String template = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        String out = IndexMdWriter.write(template, mk9());
        assertTrue(out.contains("slug: dczia-mk9-2026\n"));
        assertTrue(out.contains("  - filename: \"front.jpg\"\n"));
        assertTrue(out.contains("videos:\n  - filename: \"spin.mp4\""));
        assertTrue(out.contains("status: stub"));
    }
}
