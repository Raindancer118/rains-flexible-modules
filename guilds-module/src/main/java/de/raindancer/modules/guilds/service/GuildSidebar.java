package de.raindancer.modules.guilds.service;

import de.raindancer.modules.guilds.GuildsSettings;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.rules.LevelRule;
import de.raindancer.modules.guilds.store.GuildBook;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/**
 * The guild's lines in a member's sidebar — handed to Core's SidebarSections, so they sit under the economy's
 * balance in the one sidebar there is: the guild, its level with the experience to the next, its bank.
 */
public final class GuildSidebar {

    public static final String OWNER = "guilds";

    private final GuildBook book;
    private final GuildService guilds;
    private final LongFunction<String> money;
    private final Supplier<GuildsSettings> settings;

    public GuildSidebar(GuildBook book, GuildService guilds, LongFunction<String> money, Supplier<GuildsSettings> settings) {
        this.book = book;
        this.guilds = guilds;
        this.money = money;
        this.settings = settings;
    }

    /** Safe from any thread: reads the book's current guild and nothing else. */
    public List<Component> lines(UUID player) {
        if (!settings.get().sidebar()) {
            return List.of();
        }
        return book.guildOf(player).map(this::linesOf).orElse(List.of());
    }

    private List<Component> linesOf(Guild guild) {
        LevelRule levels = guilds.levels();
        int level = guilds.level(guild);
        NumberFormat numbers = NumberFormat.getIntegerInstance(Locale.ENGLISH);
        Component progress = levels.isTop(level)
                ? Component.text(" Level " + level + " · top", NamedTextColor.GOLD)
                : Component.text(" Level " + level + " · ", NamedTextColor.GRAY)
                        .append(Component.text(numbers.format(guild.xp()) + "/" + numbers.format(levels.toReach(level + 1))
                                + " XP", NamedTextColor.WHITE));
        return List.of(
                GuildNotifier.tag(guild).append(Component.space()).append(GuildNotifier.title(guild)),
                progress,
                Component.text(" Bank ", NamedTextColor.GRAY).append(Component.text(money.apply(guild.bank()), NamedTextColor.WHITE)));
    }
}
