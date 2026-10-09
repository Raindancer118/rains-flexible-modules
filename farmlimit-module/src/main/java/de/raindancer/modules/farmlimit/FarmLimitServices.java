package de.raindancer.modules.farmlimit;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.farmlimit.rules.FarmRule;
import de.raindancer.modules.farmlimit.service.FarmCounter;
import org.bukkit.Server;

import java.util.function.Supplier;

/** What this module built, handed to its command and listener. */
public record FarmLimitServices(
        Server server,
        Messages messages,
        Supplier<FarmLimitSettings> settings,
        FarmRule rule,
        FarmCounter counter) {
}
