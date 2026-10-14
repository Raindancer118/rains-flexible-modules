package de.raindancer.modules.moderation.model;

import java.util.List;
import java.util.Locale;

/**
 * Which of this module's families a Grim check belongs to, from the check's name — Grim names its checks by what
 * they look at ({@code Reach}, {@code FastBreak}, {@code BadPacketsX}). A check Grim adds later lands in movement,
 * which is most of what Grim checks; the flag's own name still travels with it into evidence and alerts.
 */
public final class GrimFamily {

    private static final List<String> COMBAT = List.of("reach", "hitbox", "aim", "multiinteract", "invalidinteract",
            "selfinteract", "killaura", "antikb", "antiexplosion", "velocity");
    private static final List<String> PACKETS = List.of("badpackets", "packetorder", "crash", "exploit", "post",
            "transactionorder", "multiactions", "chat", "clientbrand");

    private GrimFamily() {
    }

    public static CheckType of(String checkName) {
        String name = checkName == null ? "" : checkName.toLowerCase(Locale.ROOT);
        if (name.isEmpty()) {
            return CheckType.GRIM_MOVEMENT;
        }
        if (COMBAT.stream().anyMatch(name::startsWith)) {
            return CheckType.GRIM_COMBAT;
        }
        if (name.contains("break") || name.contains("place") || name.startsWith("ghostblock") || name.startsWith("scaffold")) {
            return CheckType.GRIM_WORLD;
        }
        if (name.startsWith("inventory")) {
            return CheckType.GRIM_INVENTORY;
        }
        if (PACKETS.stream().anyMatch(name::startsWith)) {
            return CheckType.GRIM_PACKETS;
        }
        return CheckType.GRIM_MOVEMENT;
    }
}
