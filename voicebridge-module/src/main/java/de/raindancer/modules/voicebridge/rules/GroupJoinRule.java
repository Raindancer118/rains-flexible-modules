package de.raindancer.modules.voicebridge.rules;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Whether a player without the mod may walk into a voice chat group. Simple Voice Chat's API joins
 * without asking for the password, so this module has to keep the door itself — exactly as SVC does:
 * the password, or an invite from somebody inside.
 */
public final class GroupJoinRule implements IVoiceBridgeRule {

    public enum Verdict { JOIN, NEEDS_PASSWORD, WRONG_PASSWORD, CANNOT_CHECK }

    /**
     * @param actual the group's password, or {@code null} when it could not be read
     */
    public Verdict judge(boolean hasPassword, String actual, String typed, boolean invited) {
        if (!hasPassword || invited) {
            return Verdict.JOIN;
        }
        if (typed == null || typed.isBlank()) {
            return Verdict.NEEDS_PASSWORD;
        }
        if (actual == null) {
            return Verdict.CANNOT_CHECK;
        }
        boolean same = MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8),
                typed.getBytes(StandardCharsets.UTF_8));
        return same ? Verdict.JOIN : Verdict.WRONG_PASSWORD;
    }

    @Override
    public String describe() {
        return "a locked voice chat group needs its password or an invite";
    }
}
