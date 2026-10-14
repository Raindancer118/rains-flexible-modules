package de.raindancer.modules.economy.store;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.CashPiece;
import de.raindancer.modules.economy.model.Form;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * The server's signature on its cash: an HMAC over value, form and serial with a key that never leaves
 * the server. A creative client can send any item it likes, and an item's data can say anything — but only
 * this server can write a seal that fits, so a coin worth a million that it never minted is recognised as
 * the forgery it is. The key is the network's secret {@value #SECRET} (until 2.0 the file {@code cash.key}),
 * so money minted on one server is good on every other; losing it invalidates every coin in circulation.
 */
public final class CashSeal {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] key;

    private CashSeal(byte[] key) {
        this.key = key.clone();
    }

    /** The name of the key among the network's secrets. */
    public static final String SECRET = "economy-cash-seal";

    /**
     * The network's key, made the first time — or the 1.x {@code oldFile}'s, so money already minted stays good.
     * Empty when the database cannot be reached.
     */
    public static java.util.Optional<CashSeal> fromSecrets(de.raindancer.core.data.store.Secrets secrets, Path oldFile) {
        return secrets.bytes(SECRET, 32, oldFile, de.raindancer.core.data.store.Secrets.BASE64).map(CashSeal::new);
    }

    /** A key that lasts until the server stops — for when the database is out of reach. */
    public static CashSeal temporary() {
        byte[] fresh = new byte[32];
        new SecureRandom().nextBytes(fresh);
        return new CashSeal(fresh);
    }

    /** Reads the key from a file, making one the first time. (1.x; kept for its tests and for reading old files.) */
    public static CashSeal load(Path file) {
        try {
            if (Files.exists(file)) {
                byte[] read = Base64.getDecoder().decode(Files.readString(file).strip());
                if (read.length >= 32) {
                    return new CashSeal(read);
                }
            }
            byte[] fresh = new byte[32];
            new SecureRandom().nextBytes(fresh);
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, Base64.getEncoder().encodeToString(fresh));
            return new CashSeal(fresh);
        } catch (IOException | IllegalArgumentException unreadable) {
            throw new UncheckedIOException("the cash key " + file + " could not be read or written",
                    unreadable instanceof IOException io ? io : new IOException(unreadable));
        }
    }

    public String seal(Money value, Form form, String serial) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            byte[] signed = mac.doFinal((value.minor() + "|" + form.name() + "|" + (serial == null ? "" : serial))
                    .getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signed);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is part of every Java runtime", impossible);
        }
    }

    public boolean verify(CashPiece piece) {
        if (piece == null || piece.seal() == null) {
            return false;
        }
        byte[] expected = seal(piece.each(), piece.form(), piece.serial()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, piece.seal().getBytes(StandardCharsets.UTF_8));
    }
}
