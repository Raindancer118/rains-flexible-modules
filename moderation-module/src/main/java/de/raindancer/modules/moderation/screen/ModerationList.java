package de.raindancer.modules.moderation.screen;

import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import org.bukkit.entity.Player;

/**
 * The base every moderation <em>list</em> sits on.
 *
 * <p>The same two additions {@link ModerationScreen} makes, over Core's {@link PaginatedMenu} instead
 * of over {@code Menu}. Java has one superclass per class and the paging is worth more than the shared
 * base, so this is the small duplication that buys it — four methods, and the alternative is every
 * paged screen doing its own paging, which is the framework mistake {@code PaginatedMenu} exists to
 * stop.
 *
 * @param <T> what the list is of
 */
public abstract class ModerationList<T> extends PaginatedMenu<T> implements IModerationScreen {

    private final ModerationServices services;

    protected ModerationList(ModerationServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    protected ModerationServices services() {
        return services;
    }

    /** Whether the viewer holds this permission. See {@link ModerationScreen#may}. */
    protected boolean may(ModerationPermission what) {
        return services.staffRule().may(viewer.getUniqueId(), what);
    }

    /** Says something to the viewer and closes nothing. */
    protected void tell(String key, Object... values) {
        services.messages().send(viewer, key, values);
    }
}
