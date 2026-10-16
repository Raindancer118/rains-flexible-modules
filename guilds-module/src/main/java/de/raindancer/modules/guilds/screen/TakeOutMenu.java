package de.raindancer.modules.guilds.screen;

import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.guilds.GuildsServices;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.GuildOutcome;
import de.raindancer.modules.guilds.service.BankService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

/** Taking money out: a share of what the limit leaves today, all of it, or any amount typed — up to the limit. */
public final class TakeOutMenu extends Menu implements IGuildsScreen {

    private final GuildsServices services;

    public TakeOutMenu(GuildsServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent, 3);
        this.services = services;
    }

    @Override
    protected Component title() {
        return Component.text("Take out", NamedTextColor.DARK_GRAY);
    }

    @Override
    public String breadcrumb() {
        return "Take out";
    }

    private long left() {
        UUID me = viewer.getUniqueId();
        return services.guilds().guildOf(me).map(guild -> services.bank().mayTake(guild, me)).orElse(0L);
    }

    @Override
    protected void render() {
        UUID me = viewer.getUniqueId();
        Optional<Guild> found = services.guilds().guildOf(me);
        if (found.isEmpty()) {
            band(MenuLayout.WHO, 4, Icons.of(Material.BARRIER, "<red>You are in no guild now"));
            return;
        }
        BankService bank = services.bank();
        BankService.Allowance allowance = bank.allowance(found.get(), me);
        long left = allowance.left();
        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.GOLD_BLOCK, "<white>You may take " + bank.format(left),
                "<gray>The bank holds <white>" + bank.format(Math.max(0, found.get().bank())) + "<gray>.",
                allowance.isUnlimited() ? "<gray>No daily limit for you."
                        : "<gray>" + (allowance.own() ? "Your own" : "Your rank's") + " limit: <white>"
                        + bank.format(allowance.limit()) + "<gray> a day,",
                allowance.isUnlimited() ? "" : "<white>" + bank.format(allowance.taken()) + "<gray> taken today."));
        share(1, Material.IRON_NUGGET, "A tenth", left / 10);
        share(2, Material.IRON_INGOT, "A quarter", left / 4);
        share(3, Material.GOLD_INGOT, "Half", left / 2);
        share(5, Material.GOLD_BLOCK, "All you may", left);
        band(MenuLayout.WHO, 7, left > 0, Icons.of(Material.NAME_TAG, "<white>Any amount",
                        "<gray>Up to <white>" + bank.format(left) + "<gray>.", "", "<gray>Click, then type it."),
                "There is nothing you may take right now.",
                click -> AmountPrompt.ask(services, viewer, "Take out how much?", this::left,
                        "More than you may take", amount -> {
                            take(amount);
                            open();
                        }, this::open));
    }

    private void share(int column, Material icon, String name, long minor) {
        band(MenuLayout.WHO, column, minor > 0, Icons.of(icon, "<white>" + name + ": " + services.bank().format(minor),
                        "<gray>Of what you may take today.", "", "<gray>Click to take it."),
                "There is nothing you may take right now.", click -> {
                    take(Money.of(minor));
                    refresh();
                });
    }

    private void take(Money amount) {
        GuildOutcome outcome = services.bank().withdraw(viewer.getUniqueId(), amount);
        if (outcome.isSuccess()) {
            services.messages().send(viewer, "guilds.bank.withdrawn", "amount", services.bank().format(amount.minor()));
        } else {
            services.messages().send(viewer, outcome.messageKey());
        }
    }

    @Override
    public String describe() {
        return "taking money out of the guild bank, up to the daily limit";
    }
}
