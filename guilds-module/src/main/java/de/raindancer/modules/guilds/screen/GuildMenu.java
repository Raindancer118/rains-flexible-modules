package de.raindancer.modules.guilds.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.guilds.GuildsServices;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.GuildPermission;
import de.raindancer.modules.guilds.model.Rank;
import de.raindancer.modules.guilds.rules.LevelRule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The guild page: who, money and places, the guild's own choices. Opened by /guild. Somebody in no guild gets
 * {@link NoGuildMenu} instead — see {@link #forViewer}.
 *
 * <p>The guild is looked up on every draw, so a page left open while somebody was kicked or the guild renamed
 * shows what is true now.
 */
public final class GuildMenu extends Menu implements IGuildsScreen {

    private final GuildsServices services;

    public GuildMenu(GuildsServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    /** The right first page for this player. */
    public static Menu forViewer(GuildsServices services, Player viewer) {
        return services.guilds().guildOf(viewer.getUniqueId()).isPresent()
                ? new GuildMenu(services, viewer, null)
                : new NoGuildMenu(services, viewer, null);
    }

    private Optional<Guild> guild() {
        return services.guilds().guildOf(viewer.getUniqueId());
    }

    @Override
    protected Component title() {
        return guild().map(guild -> Component.text(guild.name(), NamedTextColor.DARK_GRAY))
                .orElse(Component.text("Guild", NamedTextColor.DARK_GRAY));
    }

    @Override
    public String breadcrumb() {
        return guild().map(Guild::name).orElse("Guild");
    }

    @Override
    protected void render() {
        Optional<Guild> found = guild();
        if (found.isEmpty()) {
            band(MenuLayout.WHO, 4, Icons.of(Material.BARRIER, "<red>You are in no guild now",
                    "<gray>You left, were removed, or it was disbanded", "<gray>while this page was open."));
            return;
        }
        Guild guild = found.get();
        java.util.UUID me = viewer.getUniqueId();
        LevelRule levels = services.guilds().levels();
        int level = levels.levelOf(guild.xp());
        Rank rank = guild.rankOf(me).orElse(guild.lowestRank());

        set(MenuLayout.HEADER_SUBJECT, Icons.of(guild.colour().bannerMaterial(),
                "<white>" + escaped(guild.emblem().prefix() + guild.name()) + " <gray>[" + guild.tag() + "]",
                infoLines(guild, level, rank)));

        band(MenuLayout.WHO, 1, Icons.of(Material.PLAYER_HEAD, "<white>Members",
                        "<gray>" + guild.size() + " of " + services.guilds().memberCap(guild) + ".",
                        "", "<gray>Click to see everybody, and to promote,", "<gray>demote or remove those below you."),
                click -> new MembersMenu(services, viewer, this).open());
        boolean mayInvite = guild.may(me, GuildPermission.INVITE);
        int applications = services.guilds().applicationsTo(guild).size();
        band(MenuLayout.WHO, 3, mayInvite, Icons.of(Material.WRITABLE_BOOK, "<white>Invite and applications",
                        "<gray>" + applications + " application(s) waiting.",
                        "", "<gray>Click to invite somebody or answer them."),
                "Your rank cannot invite.", click -> new RecruitMenu(services, viewer, this).open());
        band(MenuLayout.WHO, 5, Icons.of(Material.LADDER, "<white>Ranks",
                        "<gray>" + guild.ranks().size() + " rank(s). You are <white>" + escaped(rank.name()) + "<gray>.",
                        "", "<gray>Click to see what each rank may do."),
                click -> new RanksMenu(services, viewer, this).open());
        band(MenuLayout.WHO, 7, Icons.of(Material.WHITE_BANNER, "<white>Allies and rivals",
                        "<gray>" + guild.allies() + " of " + services.relations().allySlots(guild) + " ally slot(s) used.",
                        "", "<gray>Click to see every other guild", "<gray>and how you stand to it."),
                click -> new RelationsMenu(services, viewer, this).open());

        boolean bankOpen = services.bank().isOpen();
        band(MenuLayout.RULES, 1, bankOpen, Icons.of(Material.GOLD_INGOT, "<white>Bank",
                        "<gray>Holds <white>" + services.bank().format(guild.bank()) + "<gray>.",
                        "", "<gray>Click to pay in or take out."),
                "There is no bank on this server right now.", click -> new BankMenu(services, viewer, this).open());
        boolean unlocked = services.homes().unlocked(guild);
        boolean mayUseHome = unlocked && guild.home().isPresent() && guild.may(me, GuildPermission.USE_HOME);
        band(MenuLayout.RULES, 3, mayUseHome, Icons.of(Material.ENDER_PEARL, "<white>Go to the guild home",
                        guild.home().map(home -> "<dark_gray>" + home.describe()).orElse("<gray>Not set yet."),
                        "", "<gray>Click to go. This closes the menu."),
                !unlocked ? "Unlocked at level " + services.homes().unlockedAt() + "."
                        : guild.home().isEmpty() ? "Nobody has set one yet." : "Your rank cannot use it.",
                click -> {
                    viewer.closeInventory();
                    services.messages().send(viewer, services.homes().go(viewer).messageKey());
                });
        boolean maySetHome = unlocked && guild.may(me, GuildPermission.SET_HOME);
        band(MenuLayout.RULES, 5, maySetHome, Icons.of(Material.RED_BED, "<white>Set the guild home here",
                        "<gray>Moves it to where you stand.", "", "<gray>Click to set it."),
                !unlocked ? "Unlocked at level " + services.homes().unlockedAt() + "." : "Your rank cannot set it.",
                click -> IGuildsScreen.tell(services, viewer, this, services.homes().set(viewer)));
        boolean mayRead = guild.may(me, GuildPermission.VIEW_LOG);
        band(MenuLayout.RULES, 7, mayRead, Icons.of(Material.BOOK, "<white>Log",
                        "<gray>Everything that happened, newest first.", "", "<gray>Click to read it."),
                "Your rank cannot read the log.", click -> new LogMenu(services, viewer, this, guild.id(), false).open());

        band(MenuLayout.LAND, 3, Icons.of(Material.COMPARATOR, "<white>The guild's own choices",
                        "<gray>Name, tag, colour, emblem, message of", "<gray>the day, who may join, friendly fire.",
                        "", "<gray>Click to see them; those your rank", "<gray>may change can be changed there."),
                click -> new GuildSettingsMenu(services, viewer, this).open());
        band(MenuLayout.LAND, 5, Icons.of(Material.OAK_DOOR, "<white>Leave the guild",
                        guild.leader().equals(me) && guild.size() > 1
                                ? "<gray>Hand the guild to somebody else first." : "<gray>You can be invited back.",
                        "", "<gray>You will be asked first."),
                click -> new ConfirmScreen(services, viewer, this, "<red>Leave " + escaped(guild.name()) + "?",
                        guild.size() == 1
                                ? List.of("<gray>You are the last member, so", "<gray>the guild is disbanded.")
                                : List.of("<gray>Your rank and what you put in", "<gray>the bank stay with the guild."),
                        () -> {
                            services.messages().send(viewer, services.guilds().leave(me, services.bank()).messageKey());
                            viewer.closeInventory();
                        }).open());
    }

    private List<String> infoLines(Guild guild, int level, Rank rank) {
        LevelRule levels = services.guilds().levels();
        List<String> lines = new ArrayList<>();
        lines.add("<gray>Led by <white>" + escaped(services.notifier().name(guild.leader())));
        lines.add("<gray>Level <white>" + level + (levels.isTop(level) ? " <gold>(top)" : ""));
        if (!levels.isTop(level)) {
            lines.add("<gray>" + bar(levels.progress(guild.xp())) + " <dark_gray>" + guild.xp() + "/" + levels.toReach(level + 1));
        }
        lines.add("<gray>Joining: <white>" + guild.joinMode().title());
        lines.add("<gray>Your rank: <white>" + escaped(rank.name()));
        if (!guild.motd().isBlank()) {
            lines.add("");
            lines.add("<gray>“" + escaped(guild.motd()) + "”");
        }
        return lines;
    }

    static String bar(double progress) {
        int filled = (int) Math.round(progress * 20);
        return "<green>" + "|".repeat(filled) + "<dark_gray>" + "|".repeat(20 - filled);
    }

    /** A guild's or player's own words, safe inside lore markup. */
    static String escaped(String text) {
        return de.raindancer.core.ui.text.Text.literal(text);
    }

    @Override
    public String describe() {
        return "a guild's own page: members, ranks, bank, home, relations, log and choices";
    }
}
