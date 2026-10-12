package de.raindancer.modules.arena.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.arena.ArenaServices;
import de.raindancer.modules.arena.api.MatchSpec;
import de.raindancer.modules.arena.api.Mode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Which kind of fight against players. */
public final class ModeMenu extends Menu implements IArenaScreen {

    private final ArenaServices services;

    public ModeMenu(ArenaServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Fight players");
    }

    @Override
    protected void render() {
        band(0, 0, Icons.of(Material.IRON_SWORD, "<gold>Duel", "<gray>One against one."),
                click -> pickKit(new MatchSpec(Mode.DUEL, null, 1, 0, 1, null)));
        band(0, 1, Icons.of(Material.STICK, "<yellow>Sumo", "<gray>No damage. Push them out of the ring."),
                click -> new FightOptionsMenu(services, viewer, this, new MatchSpec(Mode.SUMO, null, 1, 0, 1, null)).open());
        band(0, 2, Icons.of(Material.SHIELD, "<aqua>Teams of two", "<gray>Two against two, from the queue."),
                click -> pickKit(new MatchSpec(Mode.TEAMS, null, 1, 0, 2, null)));
        band(0, 3, Icons.of(Material.DIAMOND_SWORD, "<aqua>Teams of three", "<gray>Three against three, from the queue."),
                click -> pickKit(new MatchSpec(Mode.TEAMS, null, 1, 0, 3, null)));
        band(0, 4, Icons.of(Material.NETHERITE_SWORD, "<red>Free for all",
                "<gray>Three to eight, everybody against everybody.", "<gray>The last one standing wins."),
                click -> pickKit(new MatchSpec(Mode.FFA, null, 1, 0, 1, null)));
        band(1, 3, Icons.of(Material.CARROT_ON_A_STICK, "<light_purple>Mounted combat",
                        "<gray>Everybody on a pig, spear in hand.", "<gray>Steer with the carrot on a stick —",
                        "<gray>nobody gets off. Two to eight."),
                click -> new FightOptionsMenu(services, viewer, this, new MatchSpec(Mode.MOUNTED, null, 1, 0, 1, null)).open());
        band(1, 5, Icons.of(Material.SADDLE, "<gold>Horseback combat",
                        "<gray>Everybody on a horse, spear and shield.", "<gray>Nobody gets off. Two to eight."),
                click -> new FightOptionsMenu(services, viewer, this, new MatchSpec(Mode.HORSEBACK, null, 1, 0, 1, null)).open());
        band(1, 1, Icons.of(Material.WHITE_BANNER, "<gold>Guild war",
                        "<gray>Your guild against another guild,", "<gray>a side each — from two to five a side."),
                click -> pickKit(new MatchSpec(Mode.GUILD_WAR, null, 1, 0, 2, null)));
        band(1, 7, Icons.of(Material.TARGET, "<yellow>Guild training",
                        "<gray>Your own guild, split into two sides.", "<gray>Practice before the next war."),
                click -> pickKit(new MatchSpec(Mode.GUILD_TRAINING, null, 1, 0, 2, null)));
        band(0, 5, Icons.of(Material.OAK_BOAT, "<aqua>Naval battle",
                        "<gray>The pit floods. Everybody in a boat,", "<gray>tridents in hand — the last one afloat wins.",
                        "<gray>Two to eight, from the queue."),
                click -> new FightOptionsMenu(services, viewer, this, new MatchSpec(Mode.NAVAL, null, 1, 0, 1, null)).open());
        band(2, 4, Icons.of(Material.PLAYER_HEAD, "<gold><bold>Challenge a player",
                        "<gray>Pick somebody — here or on survival —", "<gray>then the fight, the kit and the stake.",
                        "<gray>They get a minute to accept."),
                click -> new de.raindancer.core.ui.choose.PlayerChooser(viewer, services.brand(), this, "Challenge whom?",
                        de.raindancer.modules.arena.util.OnlinePlayers.reachable(services.server(), services.core()::playersElsewhere),
                        java.util.List.of(viewer.getUniqueId()), entry -> new ChallengeMenu(services, viewer, this, entry.name()).open()).open());
    }

    private void pickKit(MatchSpec spec) {
        new KitPickMenu(services, viewer, this, services.link().isUp(),
                kit -> new FightOptionsMenu(services, viewer, this, spec.withKit(kit)).open()).open();
    }

    @Override
    public String breadcrumb() {
        return "fight players";
    }

    @Override
    public String describe() {
        return "choosing a kind of fight";
    }
}
