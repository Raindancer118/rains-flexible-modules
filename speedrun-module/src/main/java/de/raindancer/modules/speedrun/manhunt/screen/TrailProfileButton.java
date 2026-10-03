package de.raindancer.modules.speedrun.manhunt.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.profile.ProfileButton;
import de.raindancer.core.ui.profile.ProfileExtension;
import de.raindancer.modules.speedrun.manhunt.ManhuntSettings;
import de.raindancer.modules.speedrun.manhunt.tracker.TrailPreference;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.function.Supplier;

/**
 * The particle trail's on/off on a player's own {@code /profile} page — the same switch as
 * {@code /manhunt trail}. Only on your own profile, and only while the server offers the trail at all.
 */
public final class TrailProfileButton implements ProfileExtension {

    private final Supplier<ManhuntSettings> settings;
    private final Messages messages;

    public TrailProfileButton(Supplier<ManhuntSettings> settings, Messages messages) {
        this.settings = settings;
        this.messages = messages;
    }

    @Override
    public ProfileButton contribute(Player viewer, OfflinePlayer subject, Menu parent) {
        if (!viewer.getUniqueId().equals(subject.getUniqueId()) || !settings.get().trackerParticleTrail()) {
            return null;
        }
        boolean on = TrailPreference.shows(viewer, settings.get());
        return new ProfileButton(Icons.of(on ? Material.GLOWSTONE_DUST : Material.GUNPOWDER,
                "<gold>Compass particle trail",
                on ? "<green>On" : "<red>Off",
                "<gray>The dotted line to whatever your compass,",
                "<gray>or a clicked position, points at.",
                "<dark_gray>Click to switch."),
                click -> {
                    TrailPreference.Toggled toggled = TrailPreference.toggle(viewer, settings.get());
                    if (messages != null) {
                        messages.send(viewer, TrailPreference.messageKey(toggled));
                    }
                    parent.refresh();
                });
    }
}
