package org.snurkle.badgeturntable.remote;

import org.snurkle.badgeturntable.jobs.ApiException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Pairing codes and tokens (Remote-API.md, Pairing and tokens). The code on the Capture
 * screen changes every 2 minutes and after each pairing; five wrong codes in a row lock
 * pairing for a minute. Tokens are 128 random bits. Only their SHA-256 hashes are stored,
 * so the preferences file doesn't hold anything that would let someone else connect.
 */
public final class Pairing {
    public static final long CODE_LIFETIME_MS = 120_000;
    static final int MAX_BAD_CODES = 5;
    static final long LOCK_MS = 60_000;

    /** A paired PC, as listed in Settings → Remote control. */
    public static final class Client {
        public final String tokenHash;
        public final String clientName;
        public final long pairedAtMs;

        public Client(String tokenHash, String clientName, long pairedAtMs) {
            this.tokenHash = tokenHash;
            this.clientName = clientName;
            this.pairedAtMs = pairedAtMs;
        }
    }

    public interface Store {
        List<Client> load();

        void save(List<Client> clients);
    }

    public interface CodeListener {
        void onCodeChanged(String code);
    }

    private final Store store;
    private final LongSupplier clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Client> clients = new LinkedHashMap<>();
    private CodeListener codeListener;
    private String code;
    private long codeExpiresAt;
    private int badCodes;
    private long lockedUntil;

    public Pairing(Store store, LongSupplier clock) {
        this.store = store;
        this.clock = clock;
        for (Client c : store.load()) clients.put(c.tokenHash, c);
    }

    public synchronized void setCodeListener(CodeListener l) {
        codeListener = l;
    }

    /** The current code, made fresh if it has expired. */
    public synchronized String code() {
        if (code == null || clock.getAsLong() >= codeExpiresAt) rotate();
        return code;
    }

    private void rotate() {
        code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        codeExpiresAt = clock.getAsLong() + CODE_LIFETIME_MS;
        CodeListener l = codeListener;
        if (l != null) l.onCodeChanged(code);
    }

    /** Swaps the code on screen for a new token. */
    public synchronized String pair(String submitted, String clientName) throws ApiException {
        String c = submitted == null ? "" : submitted.replace(" ", "").trim();
        if (!c.matches("\\d{6}")) throw ApiException.badRequest("code", "The pairing code is 6 digits.");
        long now = clock.getAsLong();
        if (now < lockedUntil) {
            throw new ApiException(429, "pairing_locked", "Too many wrong codes. Try again in a minute.")
                    .with("retryAfterMs", lockedUntil - now);
        }
        if (!MessageDigest.isEqual(c.getBytes(StandardCharsets.US_ASCII), code().getBytes(StandardCharsets.US_ASCII))) {
            badCodes++;
            if (badCodes >= MAX_BAD_CODES) {
                badCodes = 0;
                lockedUntil = now + LOCK_MS;
                throw new ApiException(429, "pairing_locked", "Too many wrong codes. Try again in a minute.")
                        .with("retryAfterMs", LOCK_MS);
            }
            throw new ApiException(401, "bad_code", "That code doesn't match the one on the phone.")
                    .with("attemptsLeft", MAX_BAD_CODES - badCodes);
        }
        badCodes = 0;
        byte[] raw = new byte[16];
        random.nextBytes(raw);
        StringBuilder token = new StringBuilder(32);
        for (byte b : raw) token.append(String.format(Locale.ROOT, "%02x", b));
        String name = clientName == null || clientName.trim().isEmpty() ? "Unnamed PC" : clientName.trim();
        if (name.length() > 64) name = name.substring(0, 64);
        Client client = new Client(hash(token.toString()), name, System.currentTimeMillis());
        clients.put(client.tokenHash, client);
        store.save(new ArrayList<>(clients.values()));
        rotate();
        return token.toString();
    }

    /** The client a token belongs to, or null if it isn't valid. Constant time per stored token. */
    public synchronized Client check(String token) {
        if (token == null || token.isEmpty()) return null;
        byte[] h = hash(token).getBytes(StandardCharsets.US_ASCII);
        Client found = null;
        for (Client c : clients.values()) {
            if (MessageDigest.isEqual(h, c.tokenHash.getBytes(StandardCharsets.US_ASCII))) found = c;
        }
        return found;
    }

    /** Forgets a token (the editor's Forget). */
    public synchronized void forget(String token) {
        Client c = check(token);
        if (c != null) revoke(c.tokenHash);
    }

    /** Revokes a paired PC (Settings → Remote control → Paired PCs). */
    public synchronized void revoke(String tokenHash) {
        if (clients.remove(tokenHash) != null) store.save(new ArrayList<>(clients.values()));
    }

    public synchronized List<Client> clients() {
        return new ArrayList<>(clients.values());
    }

    static String hash(String token) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
