package de.raindancer.modules.names.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.names.NamesSettings;
import de.raindancer.modules.names.model.NameStyle;
import de.raindancer.modules.names.util.Naming;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;

/**
 * Naming a mob with a styled tag paints the mob's name too.
 *
 * <h2>Why a tick later, and why the name is recomputed rather than read</h2>
 * The interaction event fires <em>before</em> Minecraft applies the name, so there is nothing to repaint
 * yet. Waiting a tick and then setting the name this module computed — rather than reading back what
 * vanilla wrote and restyling that — means the result does not depend on how vanilla chose to represent
 * the name, and one failed read cannot leave the mob wearing half a style.
 *
 * <p>The check that the mob ended up named at all is what keeps this honest: if the interaction was
 * refused, by a claim, by the mob not being nameable, or by anything else, nothing was applied and
 * nothing is overwritten.
 *
 * <p>The tick is taken on the <em>mob's</em> scheduler through Core's {@code Scheduling}, not the
 * server's: on Folia the entity belongs to a region thread, and touching it from anywhere else is the
 * kind of mistake that only shows up on the one server that runs Folia.
 */
public final class MobNameService implements INamesService {

    private final Plugin plugin;
    private volatile NamesSettings settings;

    public MobNameService(Plugin plugin, NamesSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    @Override
    public void settings(NamesSettings fresh) {
        this.settings = fresh;
    }

    /** Whether the server paints mob names at all. */
    public boolean enabled() {
        return settings.colourMobNames();
    }

    /**
     * Paints {@code name} onto {@code target} on the next tick, if it ended up named.
     *
     * @param name  the tag's own display name, as the player wrote it
     * @param style what the tag carries
     */
    public void paint(LivingEntity target, Component name, NameStyle style) {
        if (!enabled() || target == null || style.isEmpty()) {
            return;
        }
        Component painted = Naming.apply(name, style);
        Scheduling.entityLater(plugin, target, 1L, () -> {
            if (target.isValid() && target.customName() != null) {
                target.customName(painted);
            }
        });
    }

    @Override
    public String describe() {
        return "paints a mob's name when it is named with a styled tag";
    }
}
