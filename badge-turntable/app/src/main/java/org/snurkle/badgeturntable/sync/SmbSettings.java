package org.snurkle.badgeturntable.sync;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

/** Samba settings from the Settings screen. The password lives in {@link SecretStore}. */
public final class SmbSettings {
    public static final String KEY_HOST = "smb_host";
    public static final String KEY_PORT = "smb_port";
    public static final String KEY_SHARE = "smb_share";
    public static final String KEY_PATH = "smb_path";
    public static final String KEY_DOMAIN = "smb_domain";
    public static final String KEY_USER = "smb_user";
    public static final String SECRET_PASSWORD = "smb_password";

    public final String host;
    public final int port;
    public final String share;
    /** Base folder inside the share, backslash-separated, no leading/trailing slash. */
    public final String basePath;
    public final String domain;
    public final String user;
    public final String password;

    private SmbSettings(String host, int port, String share, String basePath, String domain, String user, String password) {
        this.host = host;
        this.port = port;
        this.share = share;
        this.basePath = basePath;
        this.domain = domain;
        this.user = user;
        this.password = password;
    }

    public static SmbSettings load(Context context) {
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(context);
        int port;
        try {
            port = Integer.parseInt(p.getString(KEY_PORT, "445").trim());
        } catch (NumberFormatException e) {
            port = 445;
        }
        String path = p.getString(KEY_PATH, "").trim().replace('/', '\\').replaceAll("^\\\\+|\\\\+$", "");
        String pw = new SecretStore(context).get(SECRET_PASSWORD);
        return new SmbSettings(p.getString(KEY_HOST, "").trim(), port, p.getString(KEY_SHARE, "").trim(), path,
                p.getString(KEY_DOMAIN, "").trim(), p.getString(KEY_USER, "").trim(), pw == null ? "" : pw);
    }

    public boolean isConfigured() {
        return !host.isEmpty() && !share.isEmpty();
    }

    /** Joins the base path and a relative path ("_badges/defcon/x/front.jpg") with backslashes. */
    public String remote(String relative) {
        String r = relative.replace('/', '\\');
        return basePath.isEmpty() ? r : basePath + "\\" + r;
    }
}
