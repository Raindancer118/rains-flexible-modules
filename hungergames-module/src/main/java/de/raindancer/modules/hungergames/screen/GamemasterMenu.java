package de.raindancer.modules.hungergames.screen;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.prompt.ChatPrompts;
import de.raindancer.modules.hungergames.service.SpectatorService;
import de.raindancer.modules.hungergames.store.GameSession;
import de.raindancer.modules.hungergames.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Gamemaster management: toggling one's own mode, and — for an admin — the name list that decides who
 * counts as a gamemaster at all.
 *
 * <h2>Why activation is a small interface declared here rather than a constructor parameter typed to a
 * concrete service</h2>
 * There is no {@code GamemasterService} on disk yet: {@code GamemasterStore} only persists the restore
 * data (previous game mode, whether somebody was de-opped), and deciding <em>who may</em> switch into the
 * mode, tracking who is active right now, and applying the game-mode change belongs to whoever wires this
 * module against a live {@code Server} — the same gap {@code GameControlService.Stage} papers over for the
 * arena runners. {@link Gamemasters} is that seam, kept small and screen-shaped rather than reused from
 * {@code AdminEndpoints}: that interface is package-private to {@code service} and cannot be named from
 * here at all.
 */
public final class GamemasterMenu extends Menu implements IHungerGamesScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** Everything this page needs about gamemasters, until a real service exists to answer it. */
    public interface Gamemasters {

        /** The configured name list — people who count as a gamemaster whether or not they are online. */
        List<String> names();

        /** Everybody currently switched into gamemaster mode and online right now. */
        Set<UUID> onlineActive();

        /** Whether {@code uuid} is on the name list (or otherwise recognised) at all. */
        boolean isGamemaster(UUID uuid);

        /** Whether {@code uuid} is switched into gamemaster mode right now. */
        boolean isActive(UUID uuid);

        /** @return empty on success, or the reason it was refused */
        Optional<String> activate(Player player);

        Optional<String> deactivate(Player player);

        void setMode(Player player, GameMode mode);

        /** @return error messages; empty on success */
        List<String> addName(String actor, String name);

        List<String> removeName(String actor, String name);
    }

    private final Gamemasters gamemasters;
    private final GameSession session;
    private final SpectatorService spectator;
    private final ChatPrompts prompts;

    public GamemasterMenu(Player viewer, Brand brand, Menu parent, Gamemasters gamemasters,
                          GameSession session, SpectatorService spectator, ChatPrompts prompts) {
        super(viewer, brand, parent);
        this.gamemasters = gamemasters;
        this.session = session;
        this.spectator = spectator;
        this.prompts = prompts;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<gold>Gamemasters");
    }

    @Override
    public String breadcrumb() {
        return "Gamemasters";
    }

    @Override
    protected void render() {
        boolean admin = PermissionNodes.isAdmin(viewer);
        boolean self = gamemasters.isGamemaster(viewer.getUniqueId());
        boolean activeSelf = gamemasters.isActive(viewer.getUniqueId());

        set(4, Icons.of(Material.COMPASS, "<gold>Gamemaster system",
                "<gray>Active right now: " + gamemasters.onlineActive().size(),
                "<gray>On the name list: " + gamemasters.names().size()));

        if (self) {
            set(10, Icons.of(activeSelf ? Material.LIME_DYE : Material.GRAY_DYE,
                            (activeSelf ? "<green>" : "<gray>") + "Gamemaster mode: "
                                    + (activeSelf ? "ON" : "OFF"),
                            "<gray>Click to switch it " + (activeSelf ? "off." : "on.")),
                    click -> {
                        var error = activeSelf ? gamemasters.deactivate(viewer) : gamemasters.activate(viewer);
                        // A refusal is spoken through the button's own next render — the lore above already
                        // says which state it is in, and a repeated click that changes nothing tells the
                        // viewer as much as any chat line would.
                        error.ifPresent(reason -> set(10, Icons.locked(Icons.of(Material.GRAY_DYE,
                                "<gray>Gamemaster mode", reason), reason)));
                        refresh();
                    });
        } else {
            set(10, Icons.locked(Icons.of(Material.BARRIER, "<dark_gray>Not a gamemaster",
                    "<gray>You are not on the gamemaster name list."), "Not on the name list"));
        }

        if (activeSelf) {
            set(12, Icons.of(Material.ENDER_EYE, "<aqua>Mode: Spectator",
                            "<gray>Watch without being seen."),
                    click -> gamemasters.setMode(viewer, GameMode.SPECTATOR));
            set(13, Icons.of(Material.GRASS_BLOCK, "<green>Mode: Creative",
                            "<gray>Fly and place blocks."),
                    click -> gamemasters.setMode(viewer, GameMode.CREATIVE));
            set(15, Icons.of(Material.RECOVERY_COMPASS, "<yellow>Teleport to a tribute",
                            "<gray>Opens the living-tributes screen."),
                    click -> new SpectateMenu(viewer, brand(), session, spectator).open());
        }

        if (admin) {
            set(16, Icons.of(Material.EMERALD, "<green>Add a name",
                            "<gray>Types a player name in chat and", "<gray>adds them to the list."),
                    click -> askForName());

            int slot = 27;
            for (String name : gamemasters.names()) {
                if (slot >= 45) {
                    break;
                }
                set(slot++, Icons.of(Material.PAPER, "<white>" + name,
                        "<gray>On the gamemaster name list.",
                        "<dark_gray>Right-click: remove from the list."),
                        click -> {
                            if (click.isRightClick()) {
                                gamemasters.removeName(viewer.getName(), name);
                                refresh();
                            }
                        });
            }
        }
    }

    private void askForName() {
        viewer.closeInventory();
        prompts.ask(viewer.getUniqueId(), "hungergames-gamemaster-name", Duration.ofSeconds(60),
                typed -> {
                    String name = typed.trim();
                    if (!name.isEmpty()) {
                        gamemasters.addName(viewer.getName(), name);
                    }
                    open();
                },
                this::open);
    }

    @Override
    public String describe() {
        return "who runs the round: name list, activation, and mode";
    }
}
