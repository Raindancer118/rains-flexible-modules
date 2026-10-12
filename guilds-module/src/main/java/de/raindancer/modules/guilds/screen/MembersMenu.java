package de.raindancer.modules.guilds.screen;

import de.raindancer.core.platform.util.Times;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.guilds.GuildsServices;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.Member;
import de.raindancer.modules.guilds.model.Rank;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Everybody in the guild, most senior first, online before offline within a rank. */
public final class MembersMenu extends PaginatedMenu<Member> implements IGuildsScreen {

    private final GuildsServices services;

    public MembersMenu(GuildsServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    private Optional<Guild> guild() {
        return services.guilds().guildOf(viewer.getUniqueId());
    }

    @Override
    protected Component title() {
        return Component.text("Members", NamedTextColor.DARK_GRAY);
    }

    @Override
    public String breadcrumb() {
        return "Members";
    }

    @Override
    protected List<Member> entries() {
        return guild().map(guild -> guild.members().values().stream()
                .sorted(Comparator.<Member>comparingInt(member -> -guild.rankOf(member.player()).map(Rank::weight).orElse(0))
                        .thenComparing(member -> services.notifier().online(member.player()).isEmpty())
                        .thenComparingLong(Member::joined))
                .toList()).orElse(List.of());
    }

    @Override
    protected ItemStack icon(Member member) {
        Guild guild = guild().orElseThrow();
        String rank = guild.rankOf(member.player()).map(Rank::name).orElse("?");
        boolean online = services.notifier().online(member.player()).isPresent();
        return Icons.head(member.player(), (online ? "<green>" : "<gray>") + GuildMenu.escaped(services.notifier().name(member.player())),
                "<gray>Rank: <white>" + GuildMenu.escaped(rank),
                "<gray>In the guild for <white>" + Times.describe(Duration.ofMillis(Math.max(0, System.currentTimeMillis() - member.joined()))),
                "<gray>Earned it <white>" + member.xp() + "<gray> experience",
                "<gray>Paid in <white>" + services.bank().format(member.deposited()),
                "", "<gray>Click for what you can do about them.");
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.BARRIER, "<red>You are in no guild now");
    }

    @Override
    protected void onClick(Member member, InventoryClickEvent event) {
        new MemberMenu(services, viewer, this, member.player()).open();
    }

    @Override
    public String describe() {
        return "everybody in the guild";
    }
}
