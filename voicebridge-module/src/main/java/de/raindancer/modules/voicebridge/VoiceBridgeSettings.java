package de.raindancer.modules.voicebridge;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import de.raindancer.modules.voicebridge.model.DiscordTarget;
import org.bukkit.Material;

/**
 * What an owner decides about the bridge. The bot token is deliberately not here — see
 * {@code TokenFile} for why.
 */
@Settings(id = "voicebridge", topics = {
        @Topic(path = "voicebridge", title = "Discord voice bridge", icon = Material.NOTE_BLOCK),
        @Topic(path = "voicebridge/discord", title = "Which Discord channel", icon = Material.JUKEBOX),
        @Topic(path = "voicebridge/sound", title = "Volume and delay", icon = Material.BELL),
        @Topic(path = "voicebridge/proximity", title = "Proximity mode", icon = Material.SCULK_SENSOR),
})
public record VoiceBridgeSettings(

        @In("voicebridge") @Title("Bridge switched on")
        @Describe("Off disconnects the bot from Discord. The voice chat group stays, it just stops "
                + "carrying anything to or from Discord.")
        @Key("enabled")
        boolean enabled,

        @In("voicebridge/discord") @Title("Discord server id")
        @Describe("Right click the server icon in Discord -> Copy Server ID (needs Developer Mode, "
                + "under Settings -> Advanced).")
        @Key("discord.server-id")
        String guildId,

        @In("voicebridge/discord") @Title("Discord voice channel id")
        @Describe("Right click the voice channel -> Copy Channel ID. The bot joins it and stays.")
        @Key("discord.channel-id")
        String channelId,

        @In("voicebridge") @Title("Voice chat group name")
        @Describe("The Simple Voice Chat group that is bridged. Players join it from the voice chat "
                + "group menu or with /voicebridge join; everybody in it hears Discord and is heard there.")
        @Key("group-name")
        String groupName,

        @In("voicebridge/sound") @Title("Discord, as heard in game") @Range(min = 0, max = 200)
        @Describe("Percent. Players can also turn it down for themselves in the voice chat volume menu.")
        @Key("volume.discord-in-game")
        int discordVolumePercent,

        @In("voicebridge/sound") @Title("The game, as heard on Discord") @Range(min = 0, max = 200)
        @Describe("Percent. Applied to everybody in the group speaking at once, after mixing.")
        @Key("volume.game-on-discord")
        int gameVolumePercent,

        @In("voicebridge") @Title("Tell the group who joins on Discord")
        @Describe("A chat line to the group when somebody joins or leaves the Discord channel.")
        @Key("announce-discord-joins")
        boolean announceDiscordJoins,

        @In("voicebridge/sound") @Title("Most delay before old audio is dropped") @Range(min = 2, max = 25)
        @Describe("In 20 ms steps, for game voices on their way to Discord. Higher rides out a worse "
                + "connection; lower keeps the conversation snappier. 5 is 100 ms.")
        @Key("buffer-frames")
        int bufferFrames,

        @In("voicebridge/proximity") @Title("Proximity mode")
        @Describe("Linked Discord users who play without the mod talk and listen through their own "
                + "player, exactly as if they had Simple Voice Chat: people near them hear them, they "
                + "hear people near them, and they can use voice chat groups. Needs one extra bot "
                + "token per Discord user at the same time (see discord-token.txt).")
        @Key("proximity.enabled")
        boolean proximityEnabled,

        @In("voicebridge/proximity") @Title("Lobby voice channel id")
        @Describe("Linked players join this Discord voice channel; the bot moves each into a private "
                + "channel of their own with a free proximity bot while their player is online.")
        @Key("proximity.lobby-channel-id")
        String lobbyChannelId,

        @In("voicebridge/proximity") @Title("Category for private channels")
        @Describe("Category id the private channels are made in. Empty uses the lobby's own category. "
                + "The main bot needs Manage Channels and Move Members there.")
        @Key("proximity.category-id")
        String categoryId) {

    public static final int LOUDEST = 200;

    public static final VoiceBridgeSettings DEFAULTS =
            new VoiceBridgeSettings(true, "", "", "Discord", 100, 100, true, 5, false, "", "");

    /** The lobby to watch, or empty while proximity mode is off. */
    public String activeLobby() {
        return proximityEnabled && lobbyChannelId != null ? lobbyChannelId.strip() : "";
    }

    public String categoryOrEmpty() {
        return categoryId == null ? "" : categoryId.strip();
    }

    public DiscordTarget target() {
        return new DiscordTarget(guildId, channelId);
    }

    public String groupNameOrDefault() {
        return groupName == null || groupName.isBlank() ? DEFAULTS.groupName() : groupName.strip();
    }

    public int discordVolumeClamped() {
        return Math.max(0, Math.min(LOUDEST, discordVolumePercent));
    }

    public int gameVolumeClamped() {
        return Math.max(0, Math.min(LOUDEST, gameVolumePercent));
    }

    public int bufferFramesClamped() {
        return Math.max(2, Math.min(25, bufferFrames));
    }

    /** Two frames is enough to ride out one late packet without adding noticeable delay. */
    public int prebufferFrames() {
        return Math.min(2, bufferFramesClamped());
    }

    public VoiceBridgeSettings withEnabled(boolean value) {
        return new VoiceBridgeSettings(value, guildId, channelId, groupName, discordVolumePercent, gameVolumePercent, announceDiscordJoins, bufferFrames, proximityEnabled, lobbyChannelId, categoryId);
    }

    public VoiceBridgeSettings withGuildId(String value) {
        return new VoiceBridgeSettings(enabled, value, channelId, groupName, discordVolumePercent, gameVolumePercent, announceDiscordJoins, bufferFrames, proximityEnabled, lobbyChannelId, categoryId);
    }

    public VoiceBridgeSettings withChannelId(String value) {
        return new VoiceBridgeSettings(enabled, guildId, value, groupName, discordVolumePercent, gameVolumePercent, announceDiscordJoins, bufferFrames, proximityEnabled, lobbyChannelId, categoryId);
    }

    public VoiceBridgeSettings withGroupName(String value) {
        return new VoiceBridgeSettings(enabled, guildId, channelId, value, discordVolumePercent, gameVolumePercent, announceDiscordJoins, bufferFrames, proximityEnabled, lobbyChannelId, categoryId);
    }

    public VoiceBridgeSettings withDiscordVolumePercent(int value) {
        return new VoiceBridgeSettings(enabled, guildId, channelId, groupName, value, gameVolumePercent, announceDiscordJoins, bufferFrames, proximityEnabled, lobbyChannelId, categoryId);
    }

    public VoiceBridgeSettings withGameVolumePercent(int value) {
        return new VoiceBridgeSettings(enabled, guildId, channelId, groupName, discordVolumePercent, value, announceDiscordJoins, bufferFrames, proximityEnabled, lobbyChannelId, categoryId);
    }

    public VoiceBridgeSettings withAnnounceDiscordJoins(boolean value) {
        return new VoiceBridgeSettings(enabled, guildId, channelId, groupName, discordVolumePercent, gameVolumePercent, value, bufferFrames, proximityEnabled, lobbyChannelId, categoryId);
    }

    public VoiceBridgeSettings withBufferFrames(int value) {
        return new VoiceBridgeSettings(enabled, guildId, channelId, groupName, discordVolumePercent, gameVolumePercent, announceDiscordJoins, value, proximityEnabled, lobbyChannelId, categoryId);
    }

    public VoiceBridgeSettings withProximityEnabled(boolean value) {
        return new VoiceBridgeSettings(enabled, guildId, channelId, groupName, discordVolumePercent, gameVolumePercent, announceDiscordJoins, bufferFrames, value, lobbyChannelId, categoryId);
    }

    public VoiceBridgeSettings withLobbyChannelId(String value) {
        return new VoiceBridgeSettings(enabled, guildId, channelId, groupName, discordVolumePercent, gameVolumePercent, announceDiscordJoins, bufferFrames, proximityEnabled, value, categoryId);
    }

    public VoiceBridgeSettings withCategoryId(String value) {
        return new VoiceBridgeSettings(enabled, guildId, channelId, groupName, discordVolumePercent, gameVolumePercent, announceDiscordJoins, bufferFrames, proximityEnabled, lobbyChannelId, value);
    }
}
