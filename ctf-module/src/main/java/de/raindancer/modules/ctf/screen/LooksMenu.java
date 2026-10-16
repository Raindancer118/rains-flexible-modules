package de.raindancer.modules.ctf.screen;

import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.ctf.CtfServices;
import de.raindancer.modules.ctf.model.CaptureShow;
import de.raindancer.modules.ctf.model.Trail;
import de.raindancer.modules.ctf.store.ProfileStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;

/** The trail a carrier leaves and the fireworks a capture sets off, each unlocked at a level. */
public final class LooksMenu extends Menu implements ICtfScreen {

    private final CtfServices services;

    public LooksMenu(CtfServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Your looks");
    }

    @Override
    protected void render() {
        ProfileStore.Profile profile = services.progress().profile(viewer.getUniqueId());
        int level = services.progress().level(viewer.getUniqueId());
        Trail[] trails = Trail.values();
        for (int index = 0; index < Math.min(7, trails.length); index++) {
            Trail trail = trails[index];
            boolean open = services.progress().unlocked(viewer.getUniqueId(), trail);
            boolean mine = profile.trail() == trail;
            band(1, index + 1, open, Icons.of(trail.icon(), (mine ? "<green>" : "<white>") + trail.title(),
                    "<gray>Flag trail", mine ? "<green>Yours." : "<yellow>Click to use it."),
                    "Unlocks at level " + trail.level() + " (you are " + level + ").", click -> {
                        services.progress().pick(viewer.getUniqueId(), now -> new ProfileStore.Profile(trail, now.show(),
                                now.lastClass()));
                        services.core().effects().play(viewer.getUniqueId(), Cues.CLICK);
                        refresh();
                    });
        }
        CaptureShow[] shows = CaptureShow.values();
        for (int index = 0; index < shows.length; index++) {
            CaptureShow show = shows[index];
            boolean open = services.progress().unlocked(viewer.getUniqueId(), show);
            boolean mine = profile.show() == show;
            band(3, index + 2, open, Icons.of(show.icon(), (mine ? "<green>" : "<white>") + show.title(),
                    "<gray>Capture fireworks", mine ? "<green>Yours." : "<yellow>Click to use it."),
                    "Unlocks at level " + show.level() + " (you are " + level + ").", click -> {
                        services.progress().pick(viewer.getUniqueId(), now -> new ProfileStore.Profile(now.trail(), show,
                                now.lastClass()));
                        services.core().effects().play(viewer.getUniqueId(), Cues.CLICK);
                        refresh();
                    });
        }
    }

    @Override
    public String breadcrumb() {
        return "looks";
    }

    @Override
    public String describe() {
        return "picking a trail and fireworks";
    }
}
