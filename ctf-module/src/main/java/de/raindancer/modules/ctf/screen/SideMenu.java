package de.raindancer.modules.ctf.screen;

import de.raindancer.core.social.team.TeamColour;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.ctf.CtfServices;
import de.raindancer.modules.ctf.model.Round;
import de.raindancer.modules.ctf.model.Side;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Which side somebody would like. Before a round: a wish, kept while the sides stay even. During one: a switch to a
 * smaller side.
 */
public final class SideMenu extends Menu implements ICtfScreen {

    private static final List<TeamColour> USUAL = List.of(TeamColour.RED, TeamColour.BLUE, TeamColour.LIME,
            TeamColour.YELLOW);

    private final CtfServices services;

    public SideMenu(CtfServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Your side");
    }

    @Override
    protected void render() {
        Optional<Round> round = services.rounds().round().filter(now -> now.phase().isPlaying());
        if (round.isPresent() && services.rounds().isIn(viewer.getUniqueId())) {
            Map<Integer, List<String>> roster = services.rounds().roster();
            int column = 1;
            for (Side side : round.get().sides()) {
                List<String> members = roster.getOrDefault(side.index(), List.of());
                band(2, column, Icons.of(side.colour().bannerMaterial(), side.tagged(),
                        "<gray>" + members.size() + " playing", "<yellow>Click to switch",
                        "<gray>(only to a smaller side)"), click -> {
                    if (services.rounds().switchSide(viewer, side.index())) {
                        services.messages().send(viewer, "ctf.side.switched", "side",
                                de.raindancer.core.ui.text.Markup.of(side.tagged()));
                        viewer.closeInventory();
                    } else {
                        services.messages().send(viewer, "ctf.side.cannot-switch");
                    }
                });
                column += 2;
            }
            return;
        }
        Optional<TeamColour> wish = services.rounds().wishOf(viewer.getUniqueId());
        int column = 1;
        for (TeamColour colour : USUAL) {
            boolean mine = wish.filter(colour::equals).isPresent();
            String hex = colour.textColour().asHexString();
            band(2, column, Icons.of(colour.bannerMaterial(), "<" + hex + ">" + name(colour),
                    mine ? "<green>Your wish." : "<yellow>Click to wish for it.",
                    "<gray>On maps with that colour."), click -> {
                services.rounds().wish(viewer.getUniqueId(), colour);
                services.core().effects().play(viewer.getUniqueId(), Cues.CLICK);
                refresh();
            });
            column += 2;
        }
        band(3, 4, Icons.of(Material.STRUCTURE_VOID, "<gray>No wish", "<gray>Wherever there is room."), click -> {
            services.rounds().wish(viewer.getUniqueId(), null);
            refresh();
        });
    }

    private static String name(TeamColour colour) {
        String lower = colour.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    @Override
    public String breadcrumb() {
        return "side";
    }

    @Override
    public String describe() {
        return "picking a side";
    }
}
