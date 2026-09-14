package de.raindancer.modules.manhunt;

import de.raindancer.modules.api.ReuseContract;

import java.nio.file.Path;

/**
 * That this module reaches for RainsCore rather than growing its own — see {@link ReuseContract}.
 *
 * <p>It is here because it was not, and something slipped through: both of this module's screens
 * nudged their numbers with a ±pair of candle buttons while fourteen other modules, speedrun-module
 * included, were using Core's {@code AmountChooser} for exactly that. Nothing was wrong enough to
 * fail, which is precisely the kind of drift a document cannot hold back.
 */
class ReuseTest implements ReuseContract {

    @Override
    public Path moduleSource() {
        return Path.of("src/main/java/de/raindancer/modules/manhunt");
    }
}
