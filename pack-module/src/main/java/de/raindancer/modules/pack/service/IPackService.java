package de.raindancer.modules.pack.service;

import de.raindancer.modules.pack.PackSettings;

/**
 * Something that <em>does</em> what the module was configured to do.
 *
 * <p>The counterpart to {@link de.raindancer.modules.pack.rules.IPackRule}: a rule decides and changes
 * nothing, a service changes things and decides as little as possible.
 *
 * <h2>What implementing this promises</h2>
 * <ul>
 *   <li><b>It reads its settings through {@link #settings(PackSettings)}</b> rather than holding a
 *       live view — and takes them whether or not it currently reads any. The one forgotten when it
 *       starts reading something is the one that keeps yesterday's values until the next restart.</li>
 *   <li><b>It never blocks a server thread on a network.</b> The whole reason the hash lookup is a
 *       service at all: a plugin that fetched it during {@code onEnable} would hold up the boot for as
 *       long as somebody else's web server felt like taking.</li>
 * </ul>
 */
public interface IPackService {

    /** Swaps in the settings as they are now. Called on reload. */
    void settings(PackSettings settings);

    /** What this service does, for the console line that lists what started. */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
