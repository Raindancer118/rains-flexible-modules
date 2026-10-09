package de.raindancer.modules.jobs.listener;

import org.bukkit.event.Listener;

import java.util.UUID;

/** A listener belonging to this module; {@link #forget} so nobody is remembered after they leave. */
public interface IJobsListener extends Listener {

    void forget(UUID player);
}
