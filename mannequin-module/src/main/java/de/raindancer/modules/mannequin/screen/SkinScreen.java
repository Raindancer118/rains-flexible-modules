package de.raindancer.modules.mannequin.screen;

import de.raindancer.core.ui.choose.PlayerChooser;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.mannequin.MannequinServices;
import de.raindancer.modules.mannequin.model.Mannequin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;

/**
 * Choosing whose skin a mannequin wears: Core's {@link PlayerChooser} for anybody who has already
 * joined this server, a typed username for anybody who has not, or resetting to vanilla's own
 * default profile.
 *
 * <h2>Why a typed username exists on a page that otherwise never asks for one</h2>
 * {@link PlayerChooser} can only offer players this server already knows about — {@code
 * Bukkit.getOfflinePlayers()} is the whole list, and somebody who has never connected here (a
 * well-known player picked as a joke, a friend from a different server) is not on it at any page
 * or in any search. This project's own rule for choosers — never ask for a name in chat — carries
 * its own stated exception for exactly this shape of question: nothing bounded to enumerate. A
 * duration is one example already in use elsewhere; a global Minecraft username is another.
 */
public final class SkinScreen extends Menu implements IMannequinScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** How long somebody has to type a username before the question is dropped. */
    private static final Duration TO_ANSWER = Duration.ofSeconds(60);

    private final MannequinServices services;
    private Mannequin mannequin;

    public SkinScreen(MannequinServices services, Player viewer, Mannequin mannequin, Menu parent) {
        super(viewer, services.brand(), parent, 3);
        this.services = services;
        this.mannequin = mannequin;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Skin — " + mannequin.displayName());
    }

    @Override
    public String breadcrumb() {
        return "Skin";
    }

    @Override
    protected void render() {
        set(MenuLayout.HEADER_SUBJECT, mannequin.skinSource() == null
                ? Icons.of(Material.PLAYER_HEAD, "<white>Default skin",
                        "<gray>This mannequin wears vanilla's own default profile.")
                : Icons.head(mannequin.skinSource(), "<white>Current skin",
                        "<gray>Whoever this belongs to."));

        band(MenuLayout.WHO, 3, Icons.of(Material.NAME_TAG, "<green>Choose a player",
                        "<gray>Copy somebody's current skin onto this mannequin."),
                click -> new PlayerChooser(viewer, brand(), this, "Skin for " + mannequin.displayName(),
                        List.of(), chosen -> applySkin(chosen.id())).open());

        band(MenuLayout.WHO, 5, Icons.of(Material.BARRIER, "<yellow>Reset to default",
                        "<gray>Vanilla's own default mannequin profile."),
                click -> applySkin(null));

        band(MenuLayout.WHO, 7, Icons.of(Material.WRITABLE_BOOK, "<aqua>Type a username",
                        "<gray>Anybody in Minecraft, even somebody",
                        "<gray>who has never joined this server.",
                        "",
                        "<gray>Click to type a name."),
                click -> askForUsername());
    }

    /**
     * Asks in chat, which is the one thing this menu genuinely cannot ask — see the class doc for
     * why a global username is exactly the shape of question a chooser cannot enumerate.
     */
    private void askForUsername() {
        viewer.closeInventory();
        boolean asking = services.core().prompts().ask(viewer.getUniqueId(), "mannequin", TO_ANSWER,
                answer -> {
                    if (answer == null || answer.isBlank() || answer.equalsIgnoreCase("cancel")) {
                        services.messages().send(viewer, "mannequin.skin.left-as-it-is");
                        open();
                        return;
                    }
                    services.messages().send(viewer, "mannequin.skin.looking-up", "name", answer);
                    services.mannequins().lookupAndApplySkinByUsername(mannequin.id(), answer,
                            resolvedName -> {
                                services.messages().send(viewer, "mannequin.skin.found",
                                        "name", resolvedName);
                                services.registry().get(mannequin.id())
                                        .ifPresent(current -> mannequin = current);
                                open();
                            },
                            () -> {
                                services.messages().send(viewer, "mannequin.skin.not-found",
                                        "name", answer);
                                open();
                            });
                },
                this::open);
        if (!asking) {
            services.messages().send(viewer, "mannequin.busy");
            open();
            return;
        }
        services.messages().send(viewer, "mannequin.skin.ask-name");
    }

    /**
     * Saves the pick and, if the mannequin is live, applies it through {@code
     * MannequinService#applySkin} — the one place that knows an offline player's profile needs
     * {@code completeFromCache()} before it has real textures. Duplicating those three lines here
     * instead is exactly how the offline-skin bug happened the first time: two places that both had
     * to remember the fix, and only one of them did.
     */
    private void applySkin(java.util.UUID skinSource) {
        Mannequin updated = mannequin.withSkinSource(skinSource);
        services.mannequins().save(updated);
        services.mannequins().liveEntity(mannequin.id())
                .filter(org.bukkit.entity.Mannequin.class::isInstance)
                .map(org.bukkit.entity.Mannequin.class::cast)
                .ifPresent(live -> services.mannequins().applySkin(live, skinSource));
        backToWhoeverOpenedThis();
    }

    @Override
    public String describe() {
        return "choosing whose skin a mannequin wears, or resetting it to the default";
    }
}
