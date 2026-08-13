package de.raindancer.modules.chained;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChainedModule#info()}'s {@code requires} set — pinned empty on purpose.
 *
 * <p>{@code ModuleInfo#requiring} looks the other module up in the {@code ModuleRegistry} this
 * module's own host built, and a standalone plugin's host only ever knows about the one module
 * its own jar shaded in. A {@code .requiring("speedrun")} here once looked harmless — nothing
 * co-hosts chained and speedrun inside one bundle today — but it made every real boot of
 * RainsChained skip the module entirely as "requires 'speedrun', which is not installed", even
 * with RainsSpeedrun actually running right next to it. Only a real server boot ever caught that;
 * this pins the fix so the same declaration cannot quietly come back. The real, correct guarantee
 * that RainsSpeedrun is present lives in chained-standalone's paper-plugin.yml instead, at the
 * Bukkit level, where it is actually enforced.
 */
class ChainedModuleInfoTest {

    @Test
    void declaresNoModuleRegistryRequirements() {
        assertThat(new ChainedModule().info().requires()).isEmpty();
    }
}
