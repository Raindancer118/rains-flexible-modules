package de.raindancer.modules.guilds.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.guilds.GuildsServices;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.GuildOutcome;
import de.raindancer.modules.guilds.model.GuildPermission;
import de.raindancer.modules.guilds.model.Invitation;
import de.raindancer.modules.guilds.model.Relation;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every other guild and how this one stands to it: allies first, then those asking, then rivals, then the rest.
 * Click is about alliances (ask, accept, end), right click about rivalries (declare, end).
 */
public final class RelationsMenu extends PaginatedMenu<Guild> implements IGuildsScreen {

    private final GuildsServices services;

    public RelationsMenu(GuildsServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    private Optional<Guild> own() {
        return services.guilds().guildOf(viewer.getUniqueId());
    }

    @Override
    protected Component title() {
        return Component.text("Allies and rivals", NamedTextColor.DARK_GRAY);
    }

    @Override
    public String breadcrumb() {
        return "Relations";
    }

    private boolean asking(Guild own, Guild other) {
        return services.relations().alliancesAskedOf(own).stream().map(Invitation::guild).anyMatch(other.id().value()::equals);
    }

    private int order(Guild own, Guild other) {
        Relation relation = own.relationTo(other.id());
        if (relation == Relation.ALLY) {
            return 0;
        }
        if (asking(own, other)) {
            return 1;
        }
        return relation == Relation.RIVAL ? 2 : 3;
    }

    @Override
    protected List<Guild> entries() {
        Optional<Guild> own = own();
        if (own.isEmpty()) {
            return List.of();
        }
        return services.book().all().stream()
                .filter(guild -> !guild.id().equals(own.get().id()))
                .sorted(Comparator.<Guild>comparingInt(guild -> order(own.get(), guild))
                        .thenComparing(Guild::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Override
    protected ItemStack icon(Guild other) {
        Guild own = own().orElseThrow();
        Relation relation = own.relationTo(other.id());
        boolean theyRival = other.relationTo(own.id()) == Relation.RIVAL;
        List<String> lore = new ArrayList<>();
        lore.add(switch (relation) {
            case ALLY -> "<green>Allied.";
            case RIVAL -> "<red>Your rival.";
            case NEUTRAL -> asking(own, other) ? "<yellow>They ask for an alliance." : "<gray>Neutral.";
        });
        if (theyRival) {
            lore.add("<red>They count you as a rival.");
        }
        lore.add("<gray>Level <white>" + services.guilds().level(other) + "<gray>, <white>" + other.size() + "<gray> members");
        lore.add("");
        lore.add(relation == Relation.ALLY ? "<gray>Click to end the alliance."
                : asking(own, other) ? "<gray>Click to accept the alliance." : "<gray>Click to ask for an alliance.");
        lore.add(relation == Relation.RIVAL ? "<gray>Right click to end the rivalry." : "<gray>Right click to declare them rivals.");
        Material icon = switch (relation) {
            case ALLY -> Material.LIME_BANNER;
            case RIVAL -> Material.RED_BANNER;
            case NEUTRAL -> other.colour().bannerMaterial();
        };
        return Icons.of(icon, "<white>" + GuildMenu.escaped(other.name()) + " <gray>[" + other.tag() + "]", lore);
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>No other guilds yet");
    }

    @Override
    protected void onClick(Guild other, InventoryClickEvent event) {
        Guild own = own().orElse(null);
        if (own == null) {
            services.messages().send(viewer, GuildOutcome.NO_GUILD.messageKey());
            return;
        }
        UUID me = viewer.getUniqueId();
        if (!own.may(me, GuildPermission.RELATIONS)) {
            services.messages().send(viewer, GuildOutcome.NOT_ALLOWED.messageKey());
            return;
        }
        Relation relation = own.relationTo(other.id());
        if (event.isRightClick()) {
            if (relation == Relation.RIVAL) {
                IGuildsScreen.tell(services, viewer, this, services.relations().endRivalry(me, other.id()));
            } else {
                new ConfirmScreen(services, viewer, this, "<red>Declare " + GuildMenu.escaped(other.name()) + " rivals?",
                        relation == Relation.ALLY ? List.of("<gray>Your alliance with them ends.") : List.of("<gray>Both guilds are told."),
                        () -> IGuildsScreen.tell(services, viewer, this, services.relations().declareRival(me, other.id()))).open();
            }
            return;
        }
        if (relation == Relation.ALLY) {
            new ConfirmScreen(services, viewer, this, "<red>End the alliance with " + GuildMenu.escaped(other.name()) + "?",
                    List.of("<gray>Both guilds have to agree", "<gray>to ally again."),
                    () -> IGuildsScreen.tell(services, viewer, this, services.relations().endAlliance(me, other.id()))).open();
            return;
        }
        IGuildsScreen.tell(services, viewer, this, asking(own, other)
                ? services.relations().acceptAlliance(me, other.id())
                : services.relations().askAlliance(me, other.id()));
    }

    @Override
    public String describe() {
        return "every other guild and how this one stands to it";
    }
}
