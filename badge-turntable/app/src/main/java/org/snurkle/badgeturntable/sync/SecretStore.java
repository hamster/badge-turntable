package org.snurkle.badgeturntable.sync;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Small secrets (the Samba password) encrypted with an AES key that never
 * leaves the Android Keystore. androidx.security-crypto is deprecated, so this
 * does the same job directly.
 */
public final class SecretStore {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "badge-turntable-secrets";
    private static final String PREFS = "secrets";

    private final Context context;

    public SecretStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public void put(String name, String value) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] blob = ByteBuffer.allocate(1 + iv.length + ct.length).put((byte) iv.length).put(iv).put(ct).array();
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).apply();
        } catch (Exception e) {
            throw new IllegalStateException("can't store secret", e);
        }
    }

    public String get(String name) {
        String b64 = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(name, null);
        if (b64 == null) return null;
        try {
            ByteBuffer blob = ByteBuffer.wrap(Base64.decode(b64, Base64.NO_WRAP));
            byte[] iv = new byte[blob.get()];
            blob.get(iv);
            byte[] ct = new byte[blob.remaining()];
            blob.get(ct);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;  // key reset (e.g. restored backup): treat as not set
        }
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }
}
