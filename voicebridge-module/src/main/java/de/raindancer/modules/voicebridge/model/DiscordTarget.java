package de.raindancer.modules.voicebridge.model;

/** Which Discord server, and which voice channel on it. Both are ids, pasted as text. */
public record DiscordTarget(String guildId, String channelId) {

    public DiscordTarget {
        guildId = guildId == null ? "" : guildId.strip();
        channelId = channelId == null ? "" : channelId.strip();
    }

    public static boolean isSnowflake(String id) {
        return id != null && id.length() >= 17 && id.length() <= 20 && id.chars().allMatch(Character::isDigit);
    }

    public boolean isComplete() {
        return isSnowflake(guildId) && isSnowflake(channelId);
    }
}
