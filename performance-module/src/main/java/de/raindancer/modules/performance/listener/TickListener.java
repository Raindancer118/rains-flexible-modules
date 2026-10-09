package de.raindancer.modules.performance.listener;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import de.raindancer.modules.performance.model.Spike;
import de.raindancer.modules.performance.model.TickWindow;
import de.raindancer.modules.performance.service.Sampler;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.IntSupplier;

/** Every tick's duration into the window; slow ones also into the list of spikes. Paper fires it on the server thread. */
public final class TickListener implements IPerformanceListener {

    private static final int SPIKES_KEPT = 20;

    private final TickWindow window;
    private final Sampler sampler;
    private final IntSupplier spikeMs;
    private final Deque<Spike> spikes = new ArrayDeque<>();
    private boolean threadKnown;

    public TickListener(TickWindow window, Sampler sampler, IntSupplier spikeMs) {
        this.window = window;
        this.sampler = sampler;
        this.spikeMs = spikeMs;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickEnd(ServerTickEndEvent event) {
        if (!threadKnown) {
            sampler.serverThread(Thread.currentThread().threadId());
            threadKnown = true;
        }
        double millis = event.getTickDuration();
        window.add(millis);
        if (millis >= spikeMs.getAsInt()) {
            synchronized (spikes) {
                spikes.addFirst(new Spike(System.nanoTime(), millis, event.getTickNumber()));
                while (spikes.size() > SPIKES_KEPT) {
                    spikes.removeLast();
                }
            }
        }
    }

    /** Spikes since {@code afterNanos}, newest first. */
    public List<Spike> spikesSince(long afterNanos) {
        synchronized (spikes) {
            return spikes.stream().filter(spike -> spike.endedNanos() > afterNanos).toList();
        }
    }

    @Override
    public void forget(UUID player) {
        // Nothing here is per player.
    }
}
