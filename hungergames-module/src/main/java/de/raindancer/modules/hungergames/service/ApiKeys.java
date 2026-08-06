package de.raindancer.modules.hungergames.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Generating and comparing the HTTP API's key.
 *
 * <h2>Why the comparison is not {@code String.equals}</h2>
 * {@code String.equals} returns as soon as the first differing character is found, so how long the
 * comparison takes leaks how many characters of the key somebody has already guessed correctly — a
 * timing side channel that turns "guess a forty-character key" into "guess one character forty times".
 * {@link MessageDigest#isEqual(byte[], byte[])} always inspects every byte, so how long {@link #matches}
 * takes says nothing about how close the guess was.
 *
 * <p>Not implementing {@link de.raindancer.modules.hungergames.service.IHungerGamesService}: this is a
 * pair of static functions with no instance and nothing a settings reload could change — the key it
 * compares against is handed in by the caller on every call, not held here.
 */
public final class ApiKeys {

    private static final char[] ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    private static final int KEY_LENGTH = 40;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeys() {
    }

    /** A fresh, random forty-character key. */
    public static String generate() {
        StringBuilder key = new StringBuilder(KEY_LENGTH);
        for (int i = 0; i < KEY_LENGTH; i++) {
            key.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return key.toString();
    }

    /**
     * Constant-time comparison, so timing cannot narrow down a guess. An unconfigured (blank) expected
     * key never matches anything — the API refuses every request rather than accepting whatever a
     * caller happens to send when nobody has set a key up yet.
     */
    public static boolean matches(String expected, String provided) {
        if (expected == null || expected.isBlank() || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
