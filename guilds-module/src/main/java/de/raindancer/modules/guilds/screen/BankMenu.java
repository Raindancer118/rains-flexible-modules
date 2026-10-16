package de.raindancer.modules.guilds.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.guilds.GuildsServices;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.GuildOutcome;
import de.raindancer.modules.guilds.model.GuildPermission;
import de.raindancer.modules.guilds.model.Member;
import de.raindancer.modules.guilds.service.BankService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

/** The bank: what is in it, paying in, taking out, and the statement. */
public final class BankMenu extends Menu implements IGuildsScreen {

    private final GuildsServices services;

    public BankMenu(GuildsServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent, 3);
        this.services = services;
    }

    @Override
    protected Component title() {
        return Component.text("Bank", NamedTextColor.DARK_GRAY);
    }

    @Override
    public String breadcrumb() {
        return "Bank";
    }

    @Override
    protected void render() {
        Optional<Guild> found = services.guilds().guildOf(viewer.getUniqueId());
        if (found.isEmpty()) {
            band(MenuLayout.WHO, 4, Icons.of(Material.BARRIER, "<red>You are in no guild now"));
            return;
        }
        Guild guild = found.get();
        UUID me = viewer.getUniqueId();
        Member mine = guild.member(me).orElseThrow();
        boolean open = services.bank().isOpen();
        band(MenuLayout.WHO, 1, open, Icons.of(Material.GOLD_NUGGET, "<white>Pay in",
                        "<gray>The bank holds <white>" + services.bank().format(guild.bank()) + "<gray>.",
                        "<gray>You have paid in <white>" + services.bank().format(mine.deposited()) + "<gray> in all.",
                        "", "<gray>Click, then type an amount."),
                "There is no bank on this server right now.",
                click -> AmountPrompt.ask(services, viewer, "Pay in how much?", () -> -1, "", amount -> {
                    GuildOutcome outcome = services.bank().deposit(me, amount);
                    if (outcome.isSuccess()) {
                        services.messages().send(viewer, "guilds.bank.deposited", "amount", services.bank().format(amount.minor()));
                    } else {
                        answer(outcome);
                    }
                    open();
                }, this::open));
        BankService.Allowance allowance = services.bank().allowance(guild, me);
        boolean mayWithdraw = open && guild.may(me, GuildPermission.WITHDRAW);
        band(MenuLayout.WHO, 4, mayWithdraw, Icons.of(Material.GOLD_INGOT, "<white>Take out",
                        "<gray>You may take <white>" + services.bank().format(allowance.left()) + "<gray> today.",
                        allowance.isUnlimited() ? "<gray>No daily limit for you."
                                : "<gray>" + (allowance.own() ? "Your own" : "Your rank's") + " limit: <white>"
                                + services.bank().format(allowance.limit()) + "<gray> a day.",
                        "", "<gray>Click to choose how much."),
                open ? "Your rank cannot take money out." : "There is no bank on this server right now.",
                click -> new TakeOutMenu(services, viewer, this).open());
        boolean mayRead = guild.may(me, GuildPermission.VIEW_LOG);
        band(MenuLayout.WHO, 7, mayRead, Icons.of(Material.PAPER, "<white>Statement",
                        "<gray>Every payment in and out.", "", "<gray>Click to read it."),
                "Your rank cannot read the log.", click -> new LogMenu(services, viewer, this, guild.id(), true).open());
    }

    private void answer(GuildOutcome outcome) {
        services.messages().send(viewer, outcome.messageKey());
    }

    @Override
    public String describe() {
        return "the guild bank";
    }
}
