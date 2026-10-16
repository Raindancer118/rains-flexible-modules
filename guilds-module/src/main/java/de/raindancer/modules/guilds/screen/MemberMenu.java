package de.raindancer.modules.guilds.screen;

import de.raindancer.core.ui.choose.OptionChooser;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.guilds.GuildsServices;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.GuildOutcome;
import de.raindancer.modules.guilds.model.GuildPermission;
import de.raindancer.modules.guilds.model.Rank;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** One member: promote, demote, set a rank, remove, hand the guild over. Each button greyed with the reason. */
public final class MemberMenu extends Menu implements IGuildsScreen {

    private final GuildsServices services;
    private final UUID member;

    public MemberMenu(GuildsServices services, Player viewer, Menu parent, UUID member) {
        super(viewer, services.brand(), parent, 3);
        this.services = services;
        this.member = member;
    }

    private Optional<Guild> guild() {
        return services.guilds().guildOf(viewer.getUniqueId()).filter(guild -> guild.isMember(member));
    }

    @Override
    protected Component title() {
        return Component.text(services.notifier().name(member), NamedTextColor.DARK_GRAY);
    }

    @Override
    public String breadcrumb() {
        return services.notifier().name(member);
    }

    private String why(GuildOutcome outcome) {
        return switch (outcome) {
            case OUTRANKED -> "They rank as high as you, or higher.";
            case YOURSELF -> "That is you.";
            case NOT_ALLOWED -> "Your rank cannot do this.";
            default -> "Not possible right now.";
        };
    }

    @Override
    protected void render() {
        Optional<Guild> found = guild();
        if (found.isEmpty()) {
            band(MenuLayout.WHO, 4, Icons.of(Material.BARRIER, "<red>Not in your guild any more"));
            return;
        }
        Guild guild = found.get();
        UUID me = viewer.getUniqueId();
        String name = GuildMenu.escaped(services.notifier().name(member));
        String rank = guild.rankOf(member).map(Rank::name).orElse("?");

        GuildOutcome promote = services.guilds().ranks().mayActOn(guild, me, member, GuildPermission.PROMOTE);
        Optional<Rank> up = services.guilds().ranks().step(guild, member, true);
        Optional<Rank> down = services.guilds().ranks().step(guild, member, false);
        band(MenuLayout.WHO, 1, promote.isSuccess() && up.isPresent(), Icons.of(Material.LIME_DYE, "<white>Promote",
                        "<gray>" + GuildMenu.escaped(rank) + " → <white>" + up.map(each -> GuildMenu.escaped(each.name())).orElse("—"),
                        "", "<gray>Click to promote."),
                promote.isSuccess() ? "They are as high as promotion goes." : why(promote),
                click -> IGuildsScreen.tell(services, viewer, this, services.guilds().step(me, member, true)));
        band(MenuLayout.WHO, 3, promote.isSuccess() && down.isPresent(), Icons.of(Material.RED_DYE, "<white>Demote",
                        "<gray>" + GuildMenu.escaped(rank) + " → <white>" + down.map(each -> GuildMenu.escaped(each.name())).orElse("—"),
                        "", "<gray>Click to demote."),
                promote.isSuccess() ? "They are on the lowest rank." : why(promote),
                click -> IGuildsScreen.tell(services, viewer, this, services.guilds().step(me, member, false)));
        band(MenuLayout.WHO, 5, promote.isSuccess(), Icons.of(Material.LADDER, "<white>Choose a rank",
                        "<gray>Any rank below your own.", "", "<gray>Click to pick one."),
                why(promote), click -> {
                    List<Rank> below = guild.ranks().stream().filter(each -> services.guilds().ranks()
                            .mayAssign(guild, me, member, each).isSuccess()).toList();
                    new OptionChooser(viewer, services.brand(), this, "Rank for " + GuildMenu.escaped(services.notifier().name(member)),
                            below.stream().map(Rank::name).toList(), rank, chosen -> {
                        below.stream().filter(each -> each.name().equals(chosen)).findFirst().ifPresent(each ->
                                services.messages().send(viewer, services.guilds().setRank(me, member, each.id()).messageKey()));
                        open();
                    }).open();
                });
        boolean leader = guild.leader().equals(me) && !member.equals(me);
        band(MenuLayout.WHO, 7, leader, Icons.of(Material.GOLDEN_HELMET, "<white>Hand the guild over",
                        "<gray>" + name + " leads it; you become", "<gray>the most senior rank below.",
                        "", "<gray>You will be asked first."),
                "Only the leader can hand the guild over.",
                click -> new ConfirmScreen(services, viewer, this, "<red>Hand the guild to " + name + "?",
                        List.of("<gray>Only they can give it back."),
                        () -> IGuildsScreen.tell(services, viewer, this, services.guilds().handOver(me, member))).open());

        GuildOutcome limits = services.guilds().ranks().mayActOn(guild, me, member, GuildPermission.MANAGE_RANKS);
        var allowance = services.bank().allowance(guild, member);
        boolean own = guild.member(member).map(each -> each.hasOwnLimit()).orElse(false);
        band(MenuLayout.RULES, 2, limits.isSuccess(), Icons.of(Material.GOLD_NUGGET, "<white>Daily bank limit",
                        own ? "<gray>Their own: <white>" + services.bank().formatLimit(allowance.limit())
                                : "<gray>Their rank's: <white>" + services.bank().formatLimit(allowance.limit()),
                        "<gray>Their own replaces the rank's, higher or lower.",
                        "", "<gray>Click, then type an amount.", "<gray>Type <white>none<gray> for no limit,",
                        "<white>rank<gray> for the rank's again."),
                limits == GuildOutcome.NOT_ALLOWED ? "Your rank cannot manage ranks." : why(limits),
                click -> IGuildsScreen.ask(services, viewer, this, "guilds.ask.member-limit", typed ->
                        services.messages().send(viewer, services.bank().parseLimit(typed, true)
                                .map(minor -> services.guilds().limitMember(me, member, minor))
                                .orElse(GuildOutcome.INVALID_AMOUNT).messageKey()), "player", name));

        GuildOutcome kick = services.guilds().ranks().mayActOn(guild, me, member, GuildPermission.KICK);
        if (kick.isSuccess()) {
            danger(Icons.of(Material.IRON_BOOTS, "<red>Remove from the guild", "", "<gray>You will be asked first."),
                    click -> new ConfirmScreen(services, viewer, this, "<red>Remove " + name + "?",
                            List.of("<gray>They can be invited back."),
                            () -> {
                                services.messages().send(viewer, services.guilds().kick(me, member).messageKey());
                                if (parent() != null) {
                                    parent().open();
                                }
                            }).open());
        } else {
            band(MenuLayout.RULES, 4, Icons.locked(Icons.of(Material.IRON_BOOTS, "<white>Remove from the guild"), why(kick)));
        }
    }

    @Override
    public String describe() {
        return "one member, and what the viewer may do about them";
    }
}
