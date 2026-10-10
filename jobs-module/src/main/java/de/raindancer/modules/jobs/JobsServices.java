package de.raindancer.modules.jobs;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.jobs.service.GoalService;
import de.raindancer.modules.jobs.service.QuestService;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/** What this module built, handed to its command, listener and screens. */
public record JobsServices(
        Plugin plugin,
        Server server,
        RainsCore core,
        LogChannel log,
        Messages messages,
        Brand brand,
        Supplier<JobsSettings> settings,
        GoalService goals,
        QuestService quests) {
}
