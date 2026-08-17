package de.raindancer.modules.chat.service;

import de.raindancer.modules.chat.ChatSettings;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code /chat freeze} — everybody without {@link de.raindancer.modules.chat.util.PermissionNodes#BYPASS_FREEZE}
 * is refused until it is switched off again.
 *
 * <h2>Why this does not survive a restart</h2>
 * A freeze is a reaction to something happening right now. A server that restarted with chat still
 * frozen from three days ago, for a reason nobody on remembers, is a server where nobody can talk and
 * the fix is a command nobody thought to run — not a state worth writing to disk.
 */
public final class FreezeService implements IChatService {

    private final AtomicBoolean frozen = new AtomicBoolean(false);

    @Override
    public void settings(ChatSettings settings) {
        // Nothing here reads settings; the interface is implemented so this shows up beside every
        // other chat service in the console line that lists what started.
    }

    public boolean isFrozen() {
        return frozen.get();
    }

    /** @return whether this changed anything */
    public boolean freeze() {
        return frozen.compareAndSet(false, true);
    }

    /** @return whether this changed anything */
    public boolean unfreeze() {
        return frozen.compareAndSet(true, false);
    }

    @Override
    public String describe() {
        return "the /chat freeze toggle";
    }
}
