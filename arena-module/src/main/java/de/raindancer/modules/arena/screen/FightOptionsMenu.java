package de.raindancer.modules.arena.screen;

import de.raindancer.core.ui.choose.AmountChooser;
import de.raindancer.core.ui.choose.PlayerChooser;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.arena.ArenaServices;
import de.raindancer.modules.arena.api.MatchSpec;
import de.raindancer.modules.arena.api.Mode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** The last step before a fight: how many rounds, what each side puts in, then the queue or an invite. */
public final class FightOptionsMenu extends Menu implements IArenaScreen {

    private final ArenaServices services;
    private final String challenged;
    private MatchSpec spec;

    public FightOptionsMenu(ArenaServices services, Player viewer, Menu parent, MatchSpec spec) {
        this(services, viewer, parent, spec, null);
    }

    /** @param challenged somebody already picked at the lanista: the last step sends them the challenge */
    public FightOptionsMenu(ArenaServices services, Player viewer, Menu parent, MatchSpec spec, String challenged) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.spec = spec;
        this.challenged = challenged;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>" + spec.mode().title() + " · "
                + (spec.isOwnGear() ? "own gear" : spec.kit()));
    }

    @Override
    protected void render() {
        boolean rounds = spec.mode() == Mode.DUEL || spec.mode() == Mode.SUMO || spec.mode() == Mode.TEAMS;
        band(0, 0, rounds, Icons.of(Material.CLOCK, "<yellow>Best of " + spec.bestOf(),
                "<gray>Click for more rounds, right click for fewer."), "A free for all is one round.", click -> {
                    int next = click.isRightClick() ? spec.bestOf() - 2 : spec.bestOf() + 2;
                    spec = spec.withBestOf(next < 1 ? MatchSpec.MOST_ROUNDS : next > MatchSpec.MOST_ROUNDS ? 1 : next);
                    refresh();
                });
        if (spec.mode().isGuild()) {
            band(0, 2, Icons.of(Material.WHITE_BANNER, "<gold>" + spec.teamSize() + " a side",
                    "<gray>Click for more, right click for fewer."), click -> {
                        int next = spec.teamSize() + (click.isRightClick() ? -1 : 1);
                        spec = spec.withTeamSize(next > 5 ? 2 : next < 2 ? 5 : next);
                        refresh();
                    });
        }
        boolean money = services.link().isUp();
        int most = services.config().mostStake() > 0 ? services.config().mostStake() : 1_000_000;
        band(0, 1, money, Icons.of(Material.GOLD_INGOT, spec.stake() > 0 ? "<gold>Stake: " + spec.stake() : "<gold>No stake",
                        "<gray>What each player puts in. The winner takes it all;",
                        "<gray>a draw gives everybody theirs back.", "<gray>Click to set."),
                "Stakes need the survival server, which cannot be reached right now.",
                click -> new AmountChooser(viewer, services.brand(), this, "Stake per player", (int) Math.min(most, spec.stake()),
                        0, most, value -> {
                            spec = spec.withStake(value);
                            reopen();
                        }).open());
        if (challenged != null) {
            toolbar(4, Icons.of(Material.IRON_SWORD, "<gold>Challenge " + MiniMessage.miniMessage().escapeTags(challenged),
                    "<gray>They get a minute to accept.", "<gray>Somebody on survival is brought over if they do."),
                    click -> {
                        viewer.closeInventory();
                        services.invites().invite(viewer, challenged, spec);
                    });
            return;
        }
        toolbar(3, Icons.of(Material.COMPASS, "<green>Find an opponent",
                "<gray>Wait in the queue for somebody who wants the same."), click -> {
                    viewer.closeInventory();
                    services.queue().join(viewer, spec);
                });
        boolean invitable = spec.mode() == Mode.DUEL || spec.mode() == Mode.SUMO || spec.mode() == Mode.NAVAL
                || spec.mode() == Mode.MOUNTED;
        toolbar(5, invitable, Icons.of(Material.PAPER, "<aqua>Ask somebody",
                        "<gray>Somebody here in the arena, or on survival."),
                "Teams and free for all come together in the queue.",
                click -> new PlayerChooser(viewer, services.brand(), this, "Ask whom?",
                        de.raindancer.modules.arena.util.OnlinePlayers.reachable(services.server(), services.core()::playersElsewhere),
                        List.of(viewer.getUniqueId()), entry -> {
                            viewer.closeInventory();
                            services.invites().invite(viewer, entry.name(), spec);
                        }).open());
    }

    @Override
    public String breadcrumb() {
        return "the fight";
    }

    @Override
    public String describe() {
        return "choosing rounds and stake";
    }
}
