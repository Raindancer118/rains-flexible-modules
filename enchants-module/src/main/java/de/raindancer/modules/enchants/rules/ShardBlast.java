package de.raindancer.modules.enchants.rules;

import java.util.Optional;

/** How big a creeper blows up in the shard world: twice the game's, more each kind up, within a limit the server bears. */
public final class ShardBlast {

    /** Larger than this, one explosion costs the server more than a tick is worth. */
    private static final float MOST = 12f;

    private ShardBlast() {
    }

    public static float radius(float vanilla, Optional<ShardTier> kind) {
        int step = kind.map(Enum::ordinal).orElse(0);
        return Math.min(MOST, vanilla * (2.0f + 0.5f * step));
    }
}
