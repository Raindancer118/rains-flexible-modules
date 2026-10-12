package de.raindancer.modules.guilds.screen;

import de.raindancer.core.platform.util.Times;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.guilds.GuildProgressSettings;
import de.raindancer.modules.guilds.GuildsServices;
import de.raindancer.modules.guilds.model.Boost;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.XpSource;
import de.raindancer.modules.guilds.rules.LevelRule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The guild's level: how far to the next, what the next levels unlock, where its experience came from, and what
 * earns it — with the bonus the viewer gets right now. The sources a server has switched off say so.
 */
public final class LevelMenu extends Menu implements IGuildsScreen {

    private final GuildsServices services;

    public LevelMenu(GuildsServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return Component.text("Guild level", NamedTextColor.DARK_GRAY);
    }

    @Override
    public String breadcrumb() {
        return "Level";
    }

    @Override
    protected void render() {
        Optional<Guild> found = services.guilds().guildOf(viewer.getUniqueId());
        if (found.isEmpty()) {
            band(MenuLayout.WHO, 4, Icons.of(Material.BARRIER, "<red>You are in no guild now"));
            return;
        }
        Guild guild = found.get();
        LevelRule levels = services.xp().levels();
        int level = levels.levelOf(guild.xp());
        long now = System.currentTimeMillis();

        List<String> header = new ArrayList<>();
        header.add("<gray>Level <white>" + level + "<gray> of " + levels.topLevel());
        if (!levels.isTop(level)) {
            header.add(GuildMenu.bar(levels.progress(guild.xp())) + " <dark_gray>" + guild.xp() + "/" + levels.toReach(level + 1));
        }
        header.add("<gray>Your bonus right now: <white>+" + services.xp().bonusPercent(viewer.getUniqueId(), guild) + "%");
        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.EXPERIENCE_BOTTLE, "<white>Level " + level, header));

        // What the next three levels bring.
        for (int ahead = 1; ahead <= 3; ahead++) {
            int next = level + ahead;
            if (next > levels.topLevel()) {
                break;
            }
            List<String> lore = new ArrayList<>();
            lore.add("<gray>At <white>" + levels.toReach(next) + "<gray> experience");
            lore.add("<gray>Members: <white>" + levels.memberCap(next) + (levels.memberCap(next) > levels.memberCap(level) ? " <green>↑" : ""));
            lore.add("<gray>Ally slots: <white>" + levels.allySlots(next) + (levels.allySlots(next) > levels.allySlots(level) ? " <green>↑" : ""));
            if (levels.hasHome(next) && !levels.hasHome(level)) {
                lore.add("<green>Unlocks the guild home");
            }
            int styleFrom = services.settings().get().styleFromLevel();
            if (next >= styleFrom && level < styleFrom && next == Math.max(level + 1, styleFrom)) {
                lore.add("<green>Unlocks a painted tag and name (/guild style)");
            }
            band(MenuLayout.WHO, ahead * 2, Icons.of(Material.LIME_STAINED_GLASS_PANE, "<white>Level " + next, lore));
        }

        // Where it came from, most first.
        Map<XpSource, Long> totals = services.xp().progress().totals(guild.id().value());
        List<String> came = new ArrayList<>();
        totals.entrySet().stream().sorted(Map.Entry.<XpSource, Long>comparingByValue(Comparator.reverseOrder()))
                .forEach(entry -> came.add("<gray>" + entry.getKey().title() + ": <white>" + entry.getValue()));
        if (came.isEmpty()) {
            came.add("<gray>Nothing yet — go and do something.");
        }
        band(MenuLayout.RULES, 2, Icons.of(Material.BOOK, "<white>Where the experience came from", came));

        // What earns it, and how much.
        GuildProgressSettings more = services.xp().progressSettings();
        var base = services.settings().get();
        List<String> earns = new ArrayList<>();
        earns.add(line("Playing a minute (not away)", base.xpPerMinute()));
        earns.add(line("A monster", base.xpPerMonster()));
        earns.add(line("A boss", base.xpPerBoss()));
        earns.add(line("An advancement", base.xpPerAdvancement()));
        earns.add(line("An ore (× rarity)", more.perOre()));
        earns.add(line("A fish", more.perFish()));
        earns.add(line("Breeding an animal", more.perBreed()));
        earns.add(line("A villager trade", more.perTrade()));
        earns.add(line("A grown crop", more.perHarvest()));
        earns.add(line("An enchanting level spent", more.perEnchantLevel()));
        earns.add(line("A raid won", more.perRaid()));
        earns.add(line("A rival defeated", more.perRivalKill()));
        earns.add(line("1000 paid into the bank", more.perThousandDeposited()));
        earns.add("");
        earns.add("<dark_gray>Each member counts for at most " + (base.hourlyCap() <= 0 ? "any amount" : base.hourlyCap())
                + " an hour.");
        earns.add("<dark_gray>+" + more.togetherPercent() + "% per guildmate within " + more.togetherRadius()
                + " blocks, at most +" + more.togetherMost() + "%.");
        band(MenuLayout.RULES, 4, Icons.of(Material.KNOWLEDGE_BOOK, "<white>What earns experience", earns));

        Boost boost = services.xp().boost();
        band(MenuLayout.RULES, 6, Icons.of(boost.isOnAt(now) ? Material.BLAZE_POWDER : Material.GUNPOWDER,
                boost.isOnAt(now) ? "<gold>Boost: +" + boost.percent() + "%" : "<gray>No boost right now",
                boost.isOnAt(now) ? "<gray>For another <white>" + Times.describe(Duration.ofMillis(boost.until() - now))
                        : "<gray>Staff start boosts for events."));

        band(MenuLayout.LAND, 4, more.contracts(), Icons.of(Material.FILLED_MAP, "<white>This week's contracts",
                        "<gray>Goals for the whole guild that pay", "<gray>a lump of experience.", "", "<gray>Click to see them."),
                "Contracts are switched off on this server.", click -> new ContractsMenu(services, viewer, this).open());
    }

    private static String line(String what, long worth) {
        return worth <= 0 ? "<dark_gray>" + what + ": off" : "<gray>" + what + ": <white>" + worth;
    }

    @Override
    public String describe() {
        return "the guild's level, what earns experience and where it came from";
    }
}
