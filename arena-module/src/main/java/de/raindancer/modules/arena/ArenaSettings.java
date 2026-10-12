package de.raindancer.modules.arena;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.GameMode;
import org.bukkit.Material;

/**
 * What an owner can decide about the arena server.
 *
 * <p>The record <em>is</em> the schema: the file, its comments, its validation and the {@code /settings} screens all
 * come from it. Where things are — worlds, hub, pits — is not a setting but the layout, set with {@code /arena admin}.
 */
@Settings(id = "arena", topics = {
        @Topic(path = "arena", title = "Arena", icon = Material.IRON_SWORD),
})
public record ArenaSettings(

        @In("arena") @Title("Server to go back to")
        @Describe("The proxy's name for the server /arena leave sends people back to — the survival server.")
        @Key("lobby-server")
        String lobbyServer,

        @In("arena") @Title("Border past the map") @Range(min = 0, max = 64)
        @Describe("Blocks of room between a map's edge and its world's border. The arena worlds are empty "
                + "beyond the map, so whoever falls off is brought back to the hub.")
        @Key("border-margin")
        int borderMargin,

        @In("arena") @Title("Game mode in the arena")
        @Describe("What players are on this server. Adventure keeps every block where it is even before the "
                + "arena's own protection is asked. Admins build in creative.")
        @Key("game-mode")
        GameMode gameMode,

        @In("arena") @Title("Nobody goes hungry in the hub")
        @Describe("Keep hunger full outside a fight. A fight decides its own hunger.")
        @Key("hub-fed")
        boolean hubFed,

        @In("arena") @Title("Time limit of a fight") @Range(min = 1, max = 60)
        @Describe("Minutes a round may last. When it is up the fight is a draw and every stake goes back — "
                + "nobody wins money by hiding. Mob waves have no limit.")
        @Key("fight-minutes")
        int fightMinutes,

        @In("arena") @Title("Countdown before a round") @Range(min = 1, max = 15)
        @Describe("Seconds everybody stands frozen on their spawn before a round begins.")
        @Key("countdown-seconds")
        int countdownSeconds,

        @In("arena") @Title("Free for all waits for more") @Range(min = 0, max = 300)
        @Describe("Seconds a free-for-all waits after the third player for a fourth and more, before it starts "
                + "with whoever is there.")
        @Key("ffa-wait-seconds")
        int ffaWaitSeconds,

        @In("arena") @Title("Sumo ring") @Range(min = 3, max = 20)
        @Describe("Blocks from a pit's middle to the edge of the sumo ring. Pushed past it is out.")
        @Key("sumo-radius")
        int sumoRadius,

        @In("arena") @Title("Highest stake") @Range(min = 0, max = 1_000_000_000)
        @Describe("The most each player may put in, in the economy's smallest unit. Zero is no limit.")
        @Key("most-stake")
        int mostStake,

        @In("arena") @Title("Gladiator skin")
        @Describe("The name of a Minecraft account whose skin the gladiators in the hub wear under their armour. "
                + "Empty is a plain suit. Applies to gladiators placed afterwards.")
        @Key("npc-skin")
        String npcSkin) {

    public static final ArenaSettings DEFAULTS = new ArenaSettings("lillyxoxo", 6, GameMode.ADVENTURE, true, 5, 5, 20,
            6, 0, "");

    public String lobbyOrDefault() {
        return lobbyServer == null || lobbyServer.isBlank() ? "lobby" : lobbyServer.strip();
    }

    public int marginClamped() {
        return Math.max(0, Math.min(64, borderMargin));
    }

    public int fightSeconds() {
        return Math.max(1, Math.min(60, fightMinutes)) * 60;
    }

    public int countdownClamped() {
        return Math.max(1, Math.min(15, countdownSeconds));
    }

    public long ffaWaitMillis() {
        return Math.max(0, ffaWaitSeconds) * 1000L;
    }

    public int sumoRadiusClamped() {
        return Math.max(3, Math.min(20, sumoRadius));
    }

    public GameMode gameModeOrDefault() {
        return gameMode == null || gameMode == GameMode.SPECTATOR ? GameMode.ADVENTURE : gameMode;
    }

    public ArenaSettings withLobbyServer(String value) {
        return new ArenaSettings(value, borderMargin, gameMode, hubFed, fightMinutes, countdownSeconds, ffaWaitSeconds, sumoRadius, mostStake, npcSkin);
    }

    public ArenaSettings withBorderMargin(int value) {
        return new ArenaSettings(lobbyServer, value, gameMode, hubFed, fightMinutes, countdownSeconds, ffaWaitSeconds, sumoRadius, mostStake, npcSkin);
    }

    public ArenaSettings withGameMode(GameMode value) {
        return new ArenaSettings(lobbyServer, borderMargin, value, hubFed, fightMinutes, countdownSeconds, ffaWaitSeconds, sumoRadius, mostStake, npcSkin);
    }

    public ArenaSettings withHubFed(boolean value) {
        return new ArenaSettings(lobbyServer, borderMargin, gameMode, value, fightMinutes, countdownSeconds, ffaWaitSeconds, sumoRadius, mostStake, npcSkin);
    }

    public ArenaSettings withFightMinutes(int value) {
        return new ArenaSettings(lobbyServer, borderMargin, gameMode, hubFed, value, countdownSeconds, ffaWaitSeconds, sumoRadius, mostStake, npcSkin);
    }

    public ArenaSettings withCountdownSeconds(int value) {
        return new ArenaSettings(lobbyServer, borderMargin, gameMode, hubFed, fightMinutes, value, ffaWaitSeconds, sumoRadius, mostStake, npcSkin);
    }

    public ArenaSettings withFfaWaitSeconds(int value) {
        return new ArenaSettings(lobbyServer, borderMargin, gameMode, hubFed, fightMinutes, countdownSeconds, value, sumoRadius, mostStake, npcSkin);
    }

    public ArenaSettings withSumoRadius(int value) {
        return new ArenaSettings(lobbyServer, borderMargin, gameMode, hubFed, fightMinutes, countdownSeconds, ffaWaitSeconds, value, mostStake, npcSkin);
    }

    public ArenaSettings withMostStake(int value) {
        return new ArenaSettings(lobbyServer, borderMargin, gameMode, hubFed, fightMinutes, countdownSeconds, ffaWaitSeconds, sumoRadius, value, npcSkin);
    }

    public ArenaSettings withNpcSkin(String value) {
        return new ArenaSettings(lobbyServer, borderMargin, gameMode, hubFed, fightMinutes, countdownSeconds, ffaWaitSeconds, sumoRadius, mostStake, value);
    }
}
