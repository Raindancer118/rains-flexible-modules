package de.raindancer.modules.ranks.screen;

import de.raindancer.core.ui.choose.StyleEditor;
import de.raindancer.core.ui.menu.ConfirmMenu;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.ranks.RanksServices;
import de.raindancer.modules.ranks.model.Rank;
import de.raindancer.modules.ranks.rules.TagStyleRule;
import de.raindancer.modules.ranks.service.RankService.Outcome;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;

/** One rank: how its tag is painted, what it says, what it is called, how senior it is, whether it is on the team. */
public final class RankEditMenu extends Menu implements IRanksScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final TagStyleRule STYLES = new TagStyleRule();

    private final RanksServices services;
    private final String id;

    public RankEditMenu(RanksServices services, Player viewer, Menu parent, String id) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.id = id;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Rank " + MINI.escapeTags(id));
    }

    @Override
    public String breadcrumb() {
        return id;
    }

    @Override
    protected void render() {
        Optional<Rank> found = services.ranks().rank(id);
        if (found.isEmpty()) {
            band(MenuLayout.WHO, 4, Icons.of(Material.BARRIER, "<red>This rank is gone"));
            return;
        }
        Rank rank = found.get();
        band(MenuLayout.WHO, 4, Icons.of(Material.NAME_TAG, rank.tag(),
                "<gray>" + MINI.escapeTags(rank.title()) + " <dark_gray>(" + rank.id() + ")",
                "<gray>Weight <white>" + rank.weight() + "<gray>" + (rank.team() ? ", on the team." : ".")), click -> { });

        band(MenuLayout.RULES, 1, Icons.of(Material.PAINTING, "<white>Paint the tag",
                "<gray>Now: " + rank.tag(), "", "<gray>Click to pick colours, a gradient,", "<gray>bold and the rest."),
                click -> StyleEditor.of(viewer, services.brand(), this)
                        .heading("Tag colours")
                        .sample(() -> services.ranks().rank(id).map(now -> STYLES.lettersOf(now.tag())).orElse("[TAG]"))
                        .current(() -> services.ranks().rank(id).map(now -> STYLES.styleOf(now.tag()))
                                .orElse(de.raindancer.core.ui.text.NameStyle.NONE))
                        .onChange(style -> said(services.ranks().restyle(id, style)))
                        .open());
        band(MenuLayout.RULES, 3, Icons.of(Material.OAK_SIGN, "<white>Tag text",
                "<gray>" + MINI.escapeTags(STYLES.lettersOf(rank.tag())), "", "<gray>Click, then type it.",
                "<dark_gray>Plain text gets brackets; typing markup", "<dark_gray>keeps it as written."),
                click -> IRanksScreen.ask(services, viewer, this, "ranks.ask.tag",
                        typed -> said(services.ranks().retag(id, typed))));
        band(MenuLayout.RULES, 5, Icons.of(Material.WRITABLE_BOOK, "<white>Title",
                "<gray>" + MINI.escapeTags(rank.title()), "", "<gray>Click, then type it."),
                click -> IRanksScreen.ask(services, viewer, this, "ranks.ask.title",
                        typed -> said(services.ranks().rename(id, typed))));
        band(MenuLayout.RULES, 7, Icons.of(rank.team() ? Material.LIME_DYE : Material.GRAY_DYE,
                "<white>On the team: " + (rank.team() ? "<green>yes" : "<gray>no"),
                "<gray>Holders are listed on their own in the tablist.", "", "<yellow>Click<gray> to switch."),
                click -> said(services.ranks().team(id, !rank.team())));

        band(MenuLayout.LAND, 2, Icons.of(Material.ARROW, "<white>Weight: " + rank.weight(),
                "<gray>Higher is more senior, 1 to 99.", "", "<gray>Click for 1 more, right click for 1 less.",
                "<gray>Shift click for 10."),
                click -> {
                    int step = click.isShiftClick() ? 10 : 1;
                    said(services.ranks().reweigh(id, Math.clamp(rank.weight() + (click.isRightClick() ? -step : step), 1, 99)));
                });
        danger(Icons.of(Material.TNT, "<red>Delete this rank",
                "<gray>Whoever holds it shows nothing for it.", "", "<gray>You will be asked first."),
                click -> new ConfirmMenu(viewer, services.brand(), this, "<red>Delete " + MINI.escapeTags(rank.title()) + "?",
                        List.of("<gray>Everywhere on the network;", "<gray>holders simply show nothing for it."),
                        () -> {
                            said(services.ranks().delete(id));
                            if (parent() != null) {
                                parent().open();
                            } else {
                                viewer.closeInventory();
                            }
                        }).open());
    }

    private void said(Outcome outcome) {
        switch (outcome) {
            case DONE -> { }
            case NOT_SAVED -> services.messages().send(viewer, "ranks.not-saved");
            default -> services.messages().send(viewer, "ranks.invalid");
        }
        refresh();
    }

    @Override
    public String describe() {
        return "one rank: its tag, title, weight and team";
    }
}
