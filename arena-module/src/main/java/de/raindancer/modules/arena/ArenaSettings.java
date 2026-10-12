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
        boolean hubFed) {

    public static final ArenaSettings DEFAULTS = new ArenaSettings("lillyxoxo", 6, GameMode.ADVENTURE, true);

    public String lobbyOrDefault() {
        return lobbyServer == null || lobbyServer.isBlank() ? "lobby" : lobbyServer.strip();
    }

    public int marginClamped() {
        return Math.max(0, Math.min(64, borderMargin));
    }

    public GameMode gameModeOrDefault() {
        return gameMode == null || gameMode == GameMode.SPECTATOR ? GameMode.ADVENTURE : gameMode;
    }

    public ArenaSettings withLobbyServer(String value) {
        return new ArenaSettings(value, borderMargin, gameMode, hubFed);
    }

    public ArenaSettings withBorderMargin(int value) {
        return new ArenaSettings(lobbyServer, value, gameMode, hubFed);
    }

    public ArenaSettings withGameMode(GameMode value) {
        return new ArenaSettings(lobbyServer, borderMargin, value, hubFed);
    }

    public ArenaSettings withHubFed(boolean value) {
        return new ArenaSettings(lobbyServer, borderMargin, gameMode, value);
    }
}
