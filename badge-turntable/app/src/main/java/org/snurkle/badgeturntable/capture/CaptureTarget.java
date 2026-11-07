package org.snurkle.badgeturntable.capture;

import org.snurkle.badgeturntable.session.Session;

import java.io.File;
import java.io.IOException;
import java.util.Set;

/**
 * Where a capture's files go: a badge session ({@link SessionTarget}), or the unnamed set
 * of shots the phone holds for the editor (RemoteShots).
 */
public interface CaptureTarget {
    /** Where the full-resolution original (or source video) with this name goes. */
    File originalFile(String filename);

    /** Where the web copy with this name goes. */
    File webFile(String filename);

    /** File names already taken, for numbering extra shots. */
    Set<String> filenames();

    /** The spin's source video is recorded and about to be transcoded into m's web file. */
    default void encoding(Session.Media m) throws IOException {}

    /** A still or spin is written: record it. A file with the same name replaces the old one. */
    void add(Session.Media m) throws IOException;

    /** After a capture or extra shot (index.md, sync queue). */
    default void finish() throws IOException {}
}
