package de.raindancer.modules.mannequin.screen;

import de.raindancer.core.ui.choose.PlayerChooser;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.mannequin.MannequinServices;
import de.raindancer.modules.mannequin.model.Mannequin;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Choosing whose skin a mannequin wears, via Core's {@link PlayerChooser}, or resetting to
 * vanilla's default profile.
 */
public final class SkinScreen extends Menu implements IMannequinScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final MannequinServices services;
    private final Mannequin mannequin;

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
    }

    private void applySkin(java.util.UUID skinSource) {
        Mannequin updated = mannequin.withSkinSource(skinSource);
        services.mannequins().save(updated);
        services.mannequins().liveEntity(mannequin.id())
                .filter(org.bukkit.entity.Mannequin.class::isInstance)
                .map(org.bukkit.entity.Mannequin.class::cast)
                .ifPresent(live -> {
                    if (skinSource == null) {
                        live.setProfile(org.bukkit.entity.Mannequin.defaultProfile());
                        return;
                    }
                    var profile = Bukkit.getOfflinePlayer(skinSource).getPlayerProfile();
                    live.setProfile(ResolvableProfile.resolvableProfile(profile));
                });
        backToWhoeverOpenedThis();
    }

    @Override
    public String describe() {
        return "choosing whose skin a mannequin wears, or resetting it to the default";
    }
}
