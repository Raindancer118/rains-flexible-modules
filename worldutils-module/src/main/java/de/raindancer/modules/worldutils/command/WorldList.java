package de.raindancer.modules.worldutils.command;

import de.raindancer.modules.worldutils.WorldUtilsServices;
import de.raindancer.modules.worldutils.model.Dimension;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

/** Every loaded world, one clickable row each — shared by {@code /w} and {@code /worlds list}. */
final class WorldList {

    private WorldList() {
    }

    static void send(WorldUtilsServices live, CommandSender sender) {
        live.messages().send(sender, "worldutils.list.heading", "count", live.server().getWorlds().size());
        for (World world : live.server().getWorlds()) {
            live.messages().sendPlain(sender, "worldutils.list.row",
                    "world", world.getName(),
                    "dimension", Dimension.of(world.getEnvironment()).map(Dimension::label).orElse("custom"),
                    "players", world.getPlayers().size());
        }
    }
}
