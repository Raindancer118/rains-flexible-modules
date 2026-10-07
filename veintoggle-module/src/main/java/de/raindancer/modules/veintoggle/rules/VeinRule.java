package de.raindancer.modules.veintoggle.rules;

import org.bukkit.event.block.BlockBreakEvent;

/**
 * Which block breaks are Veinminer's extra ones, and which of those to refuse.
 *
 * <h2>Why by name, and not by a dependency</h2>
 * Veinminer (Miraculixx's, 2.x) fires its own subclass of {@link BlockBreakEvent} for every further
 * block of a vein, and breaks that block only if nobody cancelled it. The subclass shares
 * BlockBreakEvent's handler list, so a plain block-break listener already hears it; knowing it by
 * name means this module needs nothing of Veinminer's to compile, runs without it installed, and
 * keeps working across its versions as long as the event keeps its name.
 */
public final class VeinRule {

    /** Veinminer's event for each extra block of a vein. */
    public static final String VEIN_EVENT = "de.miraculixx.veinminer.VeinMinerEvent$VeinminerEvent";

    /** Whether this break is one Veinminer added, rather than the block somebody broke themselves. */
    public boolean isVeinBlock(BlockBreakEvent event) {
        return event != null && event.getClass() != BlockBreakEvent.class
                && VEIN_EVENT.equals(event.getClass().getName());
    }

    /**
     * Whether to refuse it: a vein block, for a player who switched vein mining off. The block they
     * broke by hand is ordinary mining and never refused here.
     */
    public boolean refuse(boolean veinBlock, boolean wantsVeins) {
        return veinBlock && !wantsVeins;
    }
}
