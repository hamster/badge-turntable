package org.snurkle.badgeturntable.sync;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Samba access via smbj. Blocking; use from a worker thread. */
public final class SmbClient implements Closeable {

    private final SmbSettings settings;
    private final SMBClient client;
    private Connection connection;
    private DiskShare share;

    public SmbClient(SmbSettings settings) {
        this.settings = settings;
        this.client = new SMBClient(SmbConfig.builder()
                .withTimeout(10, TimeUnit.SECONDS)
                .withSoTimeout(15, TimeUnit.SECONDS)
                .build());
    }

    public void open() throws IOException {
        connection = client.connect(settings.host, settings.port);
        AuthenticationContext auth = settings.user.isEmpty()
                ? AuthenticationContext.guest()
                : new AuthenticationContext(settings.user, settings.password.toCharArray(), settings.domain);
        Session session = connection.authenticate(auth);
        share = (DiskShare) session.connectShare(settings.share);
    }

    /** Quick reachability check for the pre-capture alert (5 s connect timeout). */
    public static boolean reachable(SmbSettings settings) {
        if (!settings.isConfigured()) return false;
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(settings.host, settings.port), 5000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** The Settings screen's "Test connection": each step, pass or fail. */
    public static List<String> test(SmbSettings settings) {
        List<String> out = new ArrayList<>();
        if (!settings.isConfigured()) {
            out.add("✗ Host and share are required");
            return out;
        }
        try {
            out.add("✓ Resolved " + settings.host + " to " + InetAddress.getByName(settings.host).getHostAddress());
        } catch (IOException e) {
            out.add("✗ Can't resolve " + settings.host + ": " + e.getMessage());
            return out;
        }
        try (SmbClient c = new SmbClient(settings)) {
            c.connection = c.client.connect(settings.host, settings.port);
            out.add("✓ Connected on port " + settings.port);
            AuthenticationContext auth = settings.user.isEmpty()
                    ? AuthenticationContext.guest()
                    : new AuthenticationContext(settings.user, settings.password.toCharArray(), settings.domain);
            Session session = c.connection.authenticate(auth);
            out.add("✓ Signed in" + (settings.user.isEmpty() ? " as guest" : " as " + settings.user));
            c.share = (DiskShare) session.connectShare(settings.share);
            out.add("✓ Opened share " + settings.share);
            String base = settings.basePath;
            if (!base.isEmpty()) c.mkdirs(base);
            c.share.list(base);
            out.add("✓ Listed " + (base.isEmpty() ? "the share root" : base));
            String probe = settings.remote(".badge-turntable-test");
            try (File f = c.share.openFile(probe, EnumSet.of(AccessMask.GENERIC_WRITE), null,
                    SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null);
                 OutputStream os = f.getOutputStream()) {
                os.write("ok".getBytes(StandardCharsets.UTF_8));
            }
            c.share.rm(probe);
            out.add("✓ Wrote and deleted a test file");
        } catch (Exception e) {
            out.add("✗ " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return out;
    }

    public void mkdirs(String path) {
        StringBuilder cur = new StringBuilder();
        for (String part : path.split("\\\\")) {
            if (part.isEmpty()) continue;
            if (cur.length() > 0) cur.append('\\');
            cur.append(part);
            if (!share.folderExists(cur.toString())) share.mkdir(cur.toString());
        }
    }

    public long remoteSize(String path) {
        if (!share.fileExists(path)) return -1;
        return share.getFileInformation(path).getStandardInformation().getEndOfFile();
    }

    /** Uploads to a temporary name, then renames over the target, so a half-written file never looks complete. */
    public void upload(java.io.File local, String remotePath) throws IOException {
        int slash = remotePath.lastIndexOf('\\');
        if (slash > 0) mkdirs(remotePath.substring(0, slash));
        String tmp = remotePath + ".part";
        try (File f = share.openFile(tmp, EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.DELETE), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null)) {
            try (OutputStream os = f.getOutputStream(); InputStream in = new java.io.FileInputStream(local)) {
                in.transferTo(os);
            }
            f.rename(remotePath, true);
        }
    }

    private static final Pattern BADGE_ID = Pattern.compile("(?m)^badge_id:\\s*\"?([0-9A-Fa-f]{1,3})\"?\\s*$");

    /** badge_id values from _badges/<con>/<slug>/index.md on the share, if it has a _badges folder. */
    public Set<String> scanBadgeIds() throws IOException {
        Set<String> ids = new HashSet<>();
        String badges = settings.remote("_badges");
        if (!share.folderExists(badges)) return ids;
        for (FileIdBothDirectoryInformation con : share.list(badges)) {
            if (!isDir(con)) continue;
            String conPath = badges + "\\" + con.getFileName();
            for (FileIdBothDirectoryInformation slug : share.list(conPath)) {
                if (!isDir(slug)) continue;
                String index = conPath + "\\" + slug.getFileName() + "\\index.md";
                if (!share.fileExists(index)) continue;
                try (File f = share.openFile(index, EnumSet.of(AccessMask.GENERIC_READ), null,
                        SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
                     InputStream in = f.getInputStream()) {
                    Matcher m = BADGE_ID.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    if (m.find()) ids.add(m.group(1).toUpperCase());
                }
            }
        }
        return ids;
    }

    private static boolean isDir(FileIdBothDirectoryInformation i) {
        String n = i.getFileName();
        return !n.equals(".") && !n.equals("..")
                && (i.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
    }

    @Override
    public void close() throws IOException {
        try {
            if (share != null) share.close();
            if (connection != null) connection.close();
        } finally {
            client.close();
        }
    }
}
