package org.snurkle.badgeturntable.capture;

import org.snurkle.badgeturntable.session.Session;

import java.io.File;
import java.io.IOException;
import java.util.Set;

/**
 * Where a capture's files go: a badge session ({@link SessionTarget}), or the unnamed set
 * of shots the phone holds for the editor (RemoteShots). Only web copies are kept: the
 * full-size still or source video is written to a scratch file, the web copy is made from
 * it, and the scratch file is deleted.
 */
public interface CaptureTarget {
    /** Where the full-size still (or source video) with this name is written while it's processed. */
    File scratchFile(String filename);

    /** Where the web copy with this name goes. */
    File webFile(String filename);

    /** File names already taken, for numbering extra shots. */
    Set<String> filenames();

    /** The spin's source video is recorded and about to be transcoded into m's web file. */
    default void encoding(Session.Media m) throws IOException {}

    /** A still or spin is written: record it. A file with the same name replaces the old one. */
    void add(Session.Media m) throws IOException;

    /** After a capture or extra shot. */
    default void finish() throws IOException {}
}
