package de.raindancer.modules.chained.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.speedrun.SpeedrunSession;
import de.raindancer.modules.speedrun.SpeedrunState;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.chained.ChainedServices;
import de.raindancer.modules.chained.model.ChainPair;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

/**
 * The viewer's own pair: who it is with, how far apart they may go, and — while a run is going —
 * the elapsed time and whether it is running or paused.
 */
public final class ChainStatusMenu extends Menu implements IChainedScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final ChainedServices services;

    public ChainStatusMenu(ChainedServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Your chain");
    }

    @Override
    public String breadcrumb() {
        return "Your chain";
    }

    @Override
    protected void render() {
        Optional<ChainPair> pair = services.pairs().pairOf(viewer.getUniqueId());
        if (pair.isEmpty()) {
            set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.BARRIER,
                    "<gray>You are not chained to anybody",
                    "<dark_gray>An admin pairs players together from /chain admin."));
            return;
        }

        ChainPair chain = pair.get();
        UUID partnerId = chain.otherOf(viewer.getUniqueId());
        String partnerName = Bukkit.getOfflinePlayer(partnerId).getName();

        set(MenuLayout.HEADER_SUBJECT, Icons.head(partnerId,
                "<white>Chained to " + (partnerName == null ? "somebody" : partnerName),
                "<gray>Up to " + (int) chain.maxDistance() + " blocks apart"));

        Optional<SpeedrunSession> session = services.chain().sessionOf(viewer.getUniqueId());
        if (session.isEmpty()) {
            band(MenuLayout.WHO, 4, Icons.of(Material.CLOCK, "<gray>No run is going",
                            "<dark_gray>An admin starts one from /chain admin."),
                    click -> refresh());
            return;
        }

        SpeedrunSession run = session.get();
        boolean paused = run.state() == SpeedrunState.PAUSED;
        band(MenuLayout.WHO, 4, Icons.of(paused ? Material.YELLOW_DYE : Material.LIME_DYE,
                        paused ? "<yellow>Paused" : "<green>Running",
                        "<gray>Elapsed: " + Times.brief(run.elapsed()),
                        "<dark_gray>" + Times.describe(run.elapsed())),
                click -> refresh());
    }

    @Override
    public String describe() {
        return "the viewer's own chain, and its clock";
    }
}
