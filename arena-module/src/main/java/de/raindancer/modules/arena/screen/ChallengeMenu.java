package de.raindancer.modules.arena.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.arena.ArenaServices;
import de.raindancer.modules.arena.api.MatchSpec;
import de.raindancer.modules.arena.api.Mode;
import de.raindancer.modules.arena.api.WaveSpec;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** The lanista's direct challenge: somebody is picked, now what kind of fight — then the kit and the stake. */
public final class ChallengeMenu extends Menu implements IArenaScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final ArenaServices services;
    private final String target;

    public ChallengeMenu(ArenaServices services, Player viewer, Menu parent, String target) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.target = target;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Challenge " + MINI.escapeTags(target));
    }

    @Override
    protected void render() {
        band(0, 2, Icons.of(Material.IRON_SWORD, "<gold>Duel", "<gray>One against one."),
                click -> pickKit(new MatchSpec(Mode.DUEL, null, 1, 0, 1, null)));
        band(0, 4, Icons.of(Material.STICK, "<yellow>Sumo", "<gray>No damage. Push them out of the ring."),
                click -> new FightOptionsMenu(services, viewer, this, new MatchSpec(Mode.SUMO, null, 1, 0, 1, null),
                        target).open());
        band(0, 5, Icons.of(Material.OAK_BOAT, "<aqua>Naval battle", "<gray>The pit floods; boats and tridents."),
                click -> new FightOptionsMenu(services, viewer, this, new MatchSpec(Mode.NAVAL, null, 1, 0, 1, null),
                        target).open());
        band(1, 4, Icons.of(Material.CARROT_ON_A_STICK, "<light_purple>Mounted combat", "<gray>On pigs, with spears."),
                click -> new FightOptionsMenu(services, viewer, this, new MatchSpec(Mode.MOUNTED, null, 1, 0, 1, null),
                        target).open());
        band(0, 6, Icons.of(Material.ZOMBIE_HEAD, "<dark_green>Mob waves together", "<gray>The two of you against the waves."),
                click -> pickKit(MatchSpec.waves(WaveSpec.PRESETS.get("warmup"), null)));
    }

    private void pickKit(MatchSpec spec) {
        new KitPickMenu(services, viewer, this, services.link().isUp(),
                kit -> new FightOptionsMenu(services, viewer, this, spec.withKit(kit), target).open()).open();
    }

    @Override
    public String breadcrumb() {
        return "challenge";
    }

    @Override
    public String describe() {
        return "the kind of fight for a direct challenge";
    }
}
