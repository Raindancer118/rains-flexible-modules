package de.raindancer.modules.ranks.screen;

import de.raindancer.core.data.settings.SettingsMenu;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.ranks.RanksServices;
import de.raindancer.modules.ranks.RanksSettings;
import de.raindancer.modules.ranks.model.Holder;
import de.raindancer.modules.ranks.model.Place;
import de.raindancer.modules.ranks.model.Rank;
import de.raindancer.modules.ranks.service.RankService.Outcome;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** {@code /rank}: what goes in front of your name, where it shows, and which of your ranks you wear. */
public final class RankMenu extends Menu implements IRanksScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final String SETTINGS_PERMISSION = "rainscore.settings";
    /** Room in one band for ranks to wear. */
    private static final int MOST_SHOWN = 7;

    private final RanksServices services;

    public RankMenu(RanksServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Your rank");
    }

    @Override
    public String breadcrumb() {
        return "Rank";
    }

    @Override
    protected void render() {
        RanksSettings settings = services.ranks().settings();
        Holder holder = services.ranks().holder(viewer.getUniqueId());
        List<Rank> held = services.ranks().held(viewer.getUniqueId());
        Optional<Rank> worn = services.ranks().worn(viewer.getUniqueId());

        List<String> about = new ArrayList<>();
        if (held.isEmpty()) {
            about.add("<gray>You have no rank. Staff give them out.");
        } else if (worn.isEmpty()) {
            about.add("<gray>You are wearing none of your ranks.");
        } else {
            about.add("<gray>Wearing " + worn.get().tag() + " <gray>— " + MINI.escapeTags(worn.get().title()));
        }
        band(MenuLayout.WHO, 4, Icons.head(viewer.getUniqueId(),
                worn.map(rank -> rank.tag() + " ").orElse("") + "<white>" + MINI.escapeTags(viewer.getName()), about),
                click -> { });

        for (int index = 0; index < Place.values().length; index++) {
            Place place = Place.values()[index];
            toggle(index * 2 + 2, place, settings, holder, held.isEmpty());
        }

        for (int index = 0; index < Math.min(MOST_SHOWN, held.size()); index++) {
            Rank rank = held.get(index);
            boolean wearing = worn.filter(rank::equals).isPresent();
            List<String> lore = new ArrayList<>();
            rank.description().forEach(line -> lore.add("<gray>" + MINI.escapeTags(line)));
            lore.add("");
            lore.add(wearing ? "<green>You are wearing this one." : "<yellow>Click<gray> to wear it.");
            ItemStack icon = Icons.of(wearing ? Material.GOLDEN_HELMET : Material.LEATHER_HELMET, rank.tag(), lore);
            band(MenuLayout.LAND, index + 1, settings.playersChoose(), icon, "The server picks for you", click -> {
                answer(services.ranks().wear(viewer.getUniqueId(), rank.id()));
            });
        }

        if (!held.isEmpty()) {
            boolean hidden = holder.worn().equals(Holder.HIDDEN);
            ItemStack hide = Icons.of(hidden ? Material.ENDER_EYE : Material.ENDER_PEARL,
                    hidden ? "<white>Show my rank again" : "<white>Wear no rank",
                    hidden ? "<gray>Back to your most senior one." : "<gray>Nothing in front of your name, anywhere.",
                    "", "<yellow>Click<gray> to switch.");
            toolbar(2, settings.playersChoose(), hide, "The server picks for you",
                    click -> answer(services.ranks().wear(viewer.getUniqueId(), hidden ? "" : Holder.HIDDEN)));
        }

        if (viewer.hasPermission(SETTINGS_PERMISSION)) {
            toolbar(6, Icons.of(Material.COMPARATOR, "<white>Server settings for ranks",
                            "<gray>Where ranks show for everybody, and", "<gray>whether players choose for themselves.",
                            "", "<gray>Only people with the settings permission see this."),
                    click -> new SettingsMenu(viewer, services.brand(), services.core().chatFor(services.brand()),
                            services.core().settingsNavigation(), "ranks", this).open());
        }
    }

    private void toggle(int column, Place place, RanksSettings settings, Holder holder, boolean noRank) {
        boolean serverShows = switch (place) {
            case CHAT -> settings.chat();
            case NAMETAG -> settings.nametag();
            case TABLIST -> settings.tablist();
        };
        boolean on = holder.shows(place);
        ItemStack icon = Icons.of(on ? Material.LIME_DYE : Material.GRAY_DYE,
                (on ? "<green>" : "<gray>") + place.title() + (on ? ": shown" : ": hidden"),
                "<gray>Your rank in front of your name here.", "", "<yellow>Click<gray> to switch.");
        String reason = noRank ? "You have no rank to show"
                : !serverShows ? "The server shows no ranks here"
                : "The server picks for you";
        band(MenuLayout.RULES, column, !noRank && serverShows && settings.playersChoose(), icon, reason,
                click -> answer(services.ranks().show(viewer.getUniqueId(), place, !on)));
    }

    private void answer(Outcome outcome) {
        if (outcome == Outcome.NOT_SAVED) {
            services.messages().send(viewer, "ranks.not-saved");
        } else if (outcome == Outcome.NOT_ALLOWED) {
            services.messages().send(viewer, "ranks.choosing-off");
        }
        refresh();
    }

    @Override
    protected List<String> helpLines() {
        return services.messages().lines("ranks.help").stream().map(MINI::serialize).toList();
    }

    @Override
    public String describe() {
        return "your rank, where it shows, and which one you wear";
    }
}
