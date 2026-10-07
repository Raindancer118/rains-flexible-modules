package de.raindancer.modules.voicebridge.model;

import java.util.List;

/**
 * Where the Discord side is, as one snapshot a screen or a command can read without a lock.
 *
 * @param detail         a message key explaining {@code OFF}/{@code FAILED}, or empty
 * @param channelName    the voice channel's name once known, else empty
 * @param discordMembers who is in that channel besides the bot
 */
public record BridgeStatus(Phase phase, String detail, String channelName, List<String> discordMembers) {

    public enum Phase { OFF, CONNECTING, CONNECTED, FAILED }

    public BridgeStatus {
        detail = detail == null ? "" : detail;
        channelName = channelName == null ? "" : channelName;
        discordMembers = discordMembers == null ? List.of() : List.copyOf(discordMembers);
    }

    public static BridgeStatus off(String why) {
        return new BridgeStatus(Phase.OFF, why, "", List.of());
    }

    public static BridgeStatus failed(String why) {
        return new BridgeStatus(Phase.FAILED, why, "", List.of());
    }

    public static BridgeStatus connecting() {
        return new BridgeStatus(Phase.CONNECTING, "", "", List.of());
    }

    public boolean isConnected() {
        return phase == Phase.CONNECTED;
    }
}
