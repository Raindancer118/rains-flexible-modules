package de.raindancer.modules.sends.store;

import de.raindancer.modules.sends.model.Send;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** A database that answers like the real one — moves are conditional — without being one. Shared by "servers". */
public class MemorySendStorage implements SendStorage {

    public final Map<UUID, Send> saved = new HashMap<>();
    public boolean failing;

    @Override
    public synchronized Optional<List<Send>> loadOpen() {
        return Optional.of(saved.values().stream().filter(send -> send.state().isOpen()).toList());
    }

    @Override
    public synchronized Optional<Optional<Send>> load(UUID id) {
        return Optional.of(Optional.ofNullable(saved.get(id)).filter(send -> send.state().isOpen()));
    }

    @Override
    public synchronized boolean save(Send send) {
        if (failing) {
            return false;
        }
        saved.putIfAbsent(send.id(), send);
        return true;
    }

    @Override
    public synchronized Written change(Send before, Send after) {
        if (failing) {
            return Written.FAILED;
        }
        Send now = saved.get(before.id());
        if (now == null || now.state() != before.state() || now.settledAt() != before.settledAt()) {
            return Written.LOST;
        }
        saved.put(after.id(), after);
        return Written.DONE;
    }
}
