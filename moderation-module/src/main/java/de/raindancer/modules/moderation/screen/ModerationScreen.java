package de.raindancer.modules.moderation.screen;

import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * The base every moderation screen sits on.
 *
 * <h2>What it is not</h2>
 * Not a menu framework. There is exactly one of those and it is Core's {@link Menu}. This adds only the
 * two things every moderation screen needs and nothing else has: the services, and a short way to ask
 * whether the viewer may do something — because on these screens that question is asked once per
 * button, on every render.
 *
 * <h2>The grammar these screens use the bands for</h2>
 * Core's layout gives three semantic bands. Their names come from the claims module, which had them
 * first; here they mean:
 *
 * <ul>
 *   <li><b>{@code WHO}</b> — the person. Who they are, what is on their record, what has been written
 *       about them.</li>
 *   <li><b>{@code RULES}</b> — what may be done to them. Ban, mute, freeze, kick, warn.</li>
 *   <li><b>{@code LAND}</b> — the practical: their inventory, their state, putting them right.</li>
 *   <li><b>the toolbar</b> — lifting whatever is currently in force.</li>
 *   <li><b>the danger slot</b> — the one irreversible thing, and only ever a confirmation.</li>
 * </ul>
 */
public abstract class ModerationScreen extends Menu implements IModerationScreen {

    private final ModerationServices services;

    protected ModerationScreen(ModerationServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    /** @param rows three for a dialog, which gets Back and Close only; six for a page */
    protected ModerationScreen(ModerationServices services, Player viewer, Menu parent, int rows) {
        super(viewer, services.brand(), parent, rows);
        this.services = services;
    }

    protected ModerationServices services() {
        return services;
    }

    /**
     * Whether the viewer holds this permission — for the greyed-out form of a button.
     *
     * <p>Passed straight into {@code band(band, column, allowed, item, reason, handler)} rather than
     * wrapped around one in an {@code if}. That is the difference between a button somebody can see
     * they do not have and a menu that is a different shape for everybody.
     */
    protected boolean may(ModerationPermission what) {
        return services.staffRule().may(viewer.getUniqueId(), what);
    }

    /** Whether the viewer may do this <em>to this person</em> — permission, self, and immunity. */
    protected Verdict canAct(UUID subject, ModerationPermission what) {
        return services.staffRule().canAct(viewer.getUniqueId(), subject, what);
    }

    /**
     * Says something to the viewer and closes nothing.
     *
     * <p>Screens answer in chat rather than by silently doing nothing: a button that refuses without
     * saying so is a button a player presses four more times.
     */
    protected void tell(String key, Object... values) {
        services.messages().send(viewer, key, values);
    }

    /** Says why something was refused, using the rule's own key. */
    protected void tellRefusal(Verdict verdict) {
        verdict.refusal().ifPresent(reason -> tell(reason, "detail",
                verdict.detail() == null ? "" : verdict.detail()));
    }

    /** Re-renders after a change, keeping the window open — which is what a toggle needs. */
    protected void changed() {
        refresh();
    }
}
