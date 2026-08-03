package de.raindancer.modules.claims.screen;

import de.raindancer.modules.claims.ClaimServices;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.modules.claims.model.StyledText;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * Styling one line of one title: its text, its colour, and the four decorations.
 *
 * <p>{@code StyledText} already carried {@code bold()}, {@code italic()}, {@code underlined()},
 * {@code strikethrough()} and {@code colorKey()} — nothing in the menus ever called the setters, so every
 * title on every server rendered in plain white no matter what an owner picked. This is where they are
 * wired up.
 *
 * <p>Mutates the {@link StyledText} it is given in place rather than through a setter: {@code ClaimTitles}
 * hands out the live object, not a copy, so there is nothing to write back.
 */
public final class TitleLineMenu extends ClaimScreen {

    /** The sixteen vanilla colours, cycled one at a time rather than shown as a swatch grid. */
    private static final List<NamedTextColor> COLORS = List.of(
            NamedTextColor.WHITE, NamedTextColor.GRAY, NamedTextColor.DARK_GRAY, NamedTextColor.BLACK,
            NamedTextColor.RED, NamedTextColor.DARK_RED, NamedTextColor.GOLD, NamedTextColor.YELLOW,
            NamedTextColor.GREEN, NamedTextColor.DARK_GREEN, NamedTextColor.AQUA, NamedTextColor.DARK_AQUA,
            NamedTextColor.BLUE, NamedTextColor.DARK_BLUE, NamedTextColor.LIGHT_PURPLE,
            NamedTextColor.DARK_PURPLE);

    private static final java.time.Duration PREVIEW_TIMES_FADE = Duration.ofMillis(200);
    private static final java.time.Duration PREVIEW_TIMES_STAY = Duration.ofSeconds(2);

    private final StyledText text;
    private final String label;

    public TitleLineMenu(ClaimServices services, Player viewer, Claim claim, Menu parent,
                         StyledText text, String label) {
        super(services, viewer, claim, parent);
        this.text = text;
        this.label = label;
    }

    @Override
    protected Component title() {
        return Component.text(label);
    }

    @Override
    protected void render() {
        boolean allowed = may(ClaimAdminPermission.MANAGE_TITLES);

        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.NAME_TAG, "<aqua><bold>" + label,
                text.isBlank() ? "<dark_gray>nothing set yet" : preview(),
                "",
                "<white>Colour: <yellow>" + text.colorKey()));

        band(MenuLayout.WHO, 2, allowed, Icons.of(Material.WRITABLE_BOOK, "<yellow>Change the text",
                        "<gray>You will be asked in chat.",
                        "<gray>Type <white>cancel<gray> to abort."),
                "The owner's to change",
                click -> {
                    viewer.closeInventory();
                    tell("claim.ask-title", "what", label);
                    boolean asked = services().prompts().ask(viewer.getUniqueId(), "Claims",
                            Duration.ofSeconds(30),
                            typed -> {
                                text.raw(typed);
                                save();
                                tell("claim.title-set", "what", label);
                            },
                            () -> tell("claim.title-aborted"));
                    if (!asked) {
                        tell("selection.already-being-asked");
                    }
                });

        band(MenuLayout.WHO, 5, allowed, Icons.of(Material.BARRIER, "<red>Clear this line",
                        "<gray>Empties the text; the styling stays."),
                "The owner's to change",
                click -> {
                    text.raw("");
                    save();
                });

        band(MenuLayout.RULES, 2, allowed, Icons.of(Material.WHITE_DYE,
                        "<white>Colour: <yellow>" + text.colorKey(),
                        "<gray>Left click for the next colour,",
                        "<gray>right click to go back."),
                "The owner's to change",
                click -> {
                    int direction = click.isRightClick() ? -1 : 1;
                    int at = COLORS.indexOf(text.color());
                    int next = at < 0 ? 0 : Math.floorMod(at + direction, COLORS.size());
                    text.color(COLORS.get(next));
                    save();
                });

        decoration(MenuLayout.RULES, 4, "Bold", text.bold(), text::bold);
        decoration(MenuLayout.RULES, 6, "Italic", text.italic(), text::italic);
        decoration(MenuLayout.LAND, 2, "Underlined", text.underlined(), text::underlined);
        decoration(MenuLayout.LAND, 4, "Struck through", text.strikethrough(), text::strikethrough);
        decoration(MenuLayout.LAND, 6, "Obfuscated", text.obfuscated(), text::obfuscated);

        toolbar(4, Icons.of(Material.SPYGLASS, "<yellow>Preview",
                        "<gray>Shows just this line on your screen."),
                click -> {
                    viewer.closeInventory();
                    viewer.showTitle(Title.title(text.render(), Component.empty(),
                            Title.Times.times(PREVIEW_TIMES_FADE, PREVIEW_TIMES_STAY, PREVIEW_TIMES_FADE)));
                });
    }

    private String preview() {
        return "<" + text.colorKey() + ">"
                + (text.bold() ? "<bold>" : "")
                + (text.italic() ? "<italic>" : "")
                + (text.underlined() ? "<underlined>" : "")
                + (text.strikethrough() ? "<strikethrough>" : "")
                + (text.obfuscated() ? "<obfuscated>" : "")
                + text.raw();
    }

    private void decoration(int band, int column, String name, boolean active, Consumer<Boolean> setter) {
        boolean allowed = may(ClaimAdminPermission.MANAGE_TITLES);
        band(band, column, allowed, Icons.of(Material.LEATHER, (active ? "<green>" : "<gray>") + name,
                        active ? "<green>on" : "<dark_gray>off",
                        "<yellow>Click to toggle"),
                "The owner's to change",
                click -> {
                    setter.accept(!active);
                    save();
                });
    }

    private void save() {
        claim().markDirty();
        services().claimService().saveAsync(claim());
        refresh();
    }
}
