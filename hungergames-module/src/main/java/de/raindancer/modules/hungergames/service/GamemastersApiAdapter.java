package de.raindancer.modules.hungergames.service;

import de.raindancer.modules.hungergames.screen.GamemasterMenu;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link AdminEndpoints.Gamemasters}, over the same roster the gamemaster screen edits —
 * {@link GamemasterMenu.Gamemasters} — so the API and the menu are answering from one source, not two
 * that could disagree about who is a gamemaster.
 *
 * <p>Only the names differ: this interface's {@code activeGamemasters} is that one's
 * {@code onlineActive}, and {@code isActive} without {@code isGamemaster} means the API cannot ask "is
 * this the permission-mode kind or the list kind", which the menu never needed to either.
 */
public final class GamemastersApiAdapter implements AdminEndpoints.Gamemasters {

    private final GamemasterMenu.Gamemasters gamemasters;

    public GamemastersApiAdapter(GamemasterMenu.Gamemasters gamemasters) {
        this.gamemasters = gamemasters;
    }

    @Override
    public List<String> names() {
        return gamemasters.names();
    }

    @Override
    public Set<UUID> activeGamemasters() {
        return gamemasters.onlineActive();
    }

    @Override
    public List<String> addName(String actor, String name) {
        return gamemasters.addName(actor, name);
    }

    @Override
    public List<String> removeName(String actor, String name) {
        return gamemasters.removeName(actor, name);
    }

    @Override
    public Optional<String> activate(Player player) {
        return gamemasters.activate(player);
    }

    @Override
    public Optional<String> deactivate(Player player) {
        return gamemasters.deactivate(player);
    }

    @Override
    public boolean isActive(UUID uuid) {
        return gamemasters.isActive(uuid);
    }

    @Override
    public void setMode(Player player, GameMode mode) {
        gamemasters.setMode(player, mode);
    }
}
