package de.raindancer.modules.economy.screen;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuAnimation;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.model.Game;
import de.raindancer.modules.economy.service.CrashService;
import de.raindancer.modules.economy.service.GameSounds;
import de.raindancer.modules.economy.util.Mini;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The server's crash round, live: a rising bar of glass climbing the window with the multiplier, who is in
 * and who has cashed out, and one button that bets or cashes out depending on the moment.
 */
public final class CrashMenu extends Menu implements IEconomyScreen {

    private final EconomyServices services;
    private final Bet bet;
    private double autoCashOut;
    private double lastShown = 1.0;

    CrashMenu(EconomyServices services, Player viewer, Menu parent, Bet bet) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.bet = bet.at(Game.CRASH);
    }

    public static void open(EconomyServices services, Player viewer, Menu parent) {
        new CrashMenu(services, viewer, parent, new Bet(services, viewer)).show();
    }

    /** Opens this very menu, bet and all, with its animation running. */
    private void show() {
        open();
        MenuAnimation.loop(services.plugin(), this, 4L, this::frame, () -> { });
    }

    private void frame() {
        double now = services.crash().multiplier();
        if (services.crash().phase() == CrashService.Phase.RUNNING && Math.floor(now * 10) != Math.floor(lastShown * 10)) {
            services.gambling().sounds().play(viewer.getUniqueId(), GameSounds.TICK);
        }
        lastShown = now;
        refresh();
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Crash");
    }

    @Override
    public String breadcrumb() {
        return "Crash";
    }

    @Override
    protected void render() {
        Currency currency = services.currency();
        CrashService crash = services.crash();
        CrashService.Phase phase = crash.phase();
        double at = crash.multiplier();
        Map<UUID, CrashService.Bet> bets = crash.bets();
        CrashService.Bet mine = bets.get(viewer.getUniqueId());

        String big = String.format("%.2f×", at);
        set(MenuLayout.HEADER_SUBJECT, Icons.of(phase == CrashService.Phase.CRASHED ? Material.TNT : Material.FIREWORK_ROCKET,
                (phase == CrashService.Phase.CRASHED ? "<red><bold>Crashed at " : "<green><bold>") + big,
                phase == CrashService.Phase.BETTING ? "<gray>Starts in " + crash.secondsToStart() + " s — bets are open"
                        : phase == CrashService.Phase.RUNNING ? "<gray>Climbing…" : "<gray>Next round in a moment"));
        set(MenuLayout.HEADER_LEFT, Icons.of(Material.GOLD_INGOT, "<white>Balance",
                Mini.of(currency.render(services.economy().balance(viewer.getUniqueId())))));
        StringBuilder last = new StringBuilder();
        crash.history().forEach(point -> last.append(point >= 2 ? "<green>" : "<red>").append(String.format("%.2f× ", point)));
        set(MenuLayout.HEADER_RIGHT, Icons.of(Material.CLOCK, "<white>Last rounds", last.isEmpty() ? "<gray>—" : last.toString()));

        // The climb: three columns of glass rise one row per doubling, up to the top of the window.
        int filled = phase == CrashService.Phase.BETTING ? 0 : (int) Math.min(4, Math.floor(Math.log(at) / Math.log(2) * 2) + 1);
        for (int row = 1; row <= 4; row++) {
            boolean lit = 5 - row <= filled;
            Material glass = phase == CrashService.Phase.CRASHED ? Material.RED_STAINED_GLASS
                    : lit ? Material.LIME_STAINED_GLASS : Material.BLACK_STAINED_GLASS_PANE;
            for (int column = 3; column <= 5; column++) {
                set(row * 9 + column, Icons.of(glass, (lit ? "<green>" : "<dark_gray>") + big));
            }
        }

        int line = 1;
        for (Map.Entry<UUID, CrashService.Bet> each : bets.entrySet()) {
            if (line > 4) {
                break;
            }
            String name = services.server().getOfflinePlayer(each.getKey()).getName();
            CrashService.Bet one = each.getValue();
            set(line * 9 + 7, Icons.head(each.getKey(), "<white>" + name, "<gray>" + Mini.of(currency.render(one.stake)),
                    one.cashedAt > 0 ? "<green>Out at " + String.format("%.2f×", one.cashedAt) : "<gray>In"));
            line++;
        }

        if (phase == CrashService.Phase.BETTING && mine == null) {
            set(1 * 9 + 1, bet.slip(true), click -> bet.onSlip(click, true, this::show, this::refresh));
            set(2 * 9 + 1, Icons.of(Material.REPEATER, "<white>Cash out by itself at: "
                            + (autoCashOut > 1 ? String.format("%.2f×", autoCashOut) : "never"),
                    "<yellow>Click<gray> for higher, <yellow>right click<gray> to switch off"), click -> {
                autoCashOut = click.isRightClick() ? 0 : autoCashOut < 1.5 ? 1.5 : autoCashOut < 2 ? 2
                        : autoCashOut < 3 ? 3 : autoCashOut < 5 ? 5 : autoCashOut < 10 ? 10 : 1.5;
                refresh();
            });
            toolbar(4, Icons.of(Material.LIME_CONCRETE, "<green>Join the round",
                    "<gray>" + Mini.of(currency.render(bet.amount()))), click -> {
                crash.bet(viewer, bet.amount(), autoCashOut);
                refresh();
            });
        } else if (phase == CrashService.Phase.RUNNING && mine != null && mine.cashedAt == 0) {
            toolbar(4, Icons.of(Material.GOLD_BLOCK, "<gold><bold>CASH OUT",
                    "<gray>Take " + Mini.of(currency.render(mine.stake.share(at)))), click -> {
                crash.cashOut(viewer);
                refresh();
            });
        } else {
            toolbar(4, Icons.of(Material.GRAY_CONCRETE, phase == CrashService.Phase.BETTING ? "<gray>You are in"
                    : "<gray>Wait for the next round"), click -> { });
        }
    }

    @Override
    protected List<String> helpLines() {
        return List.of("One round for everybody. The multiplier climbs", "until it crashes — cash out before it does.",
                "Your stake times the multiplier when you cash out.");
    }

    @Override
    public String describe() {
        return "the server's crash round, live";
    }
}
