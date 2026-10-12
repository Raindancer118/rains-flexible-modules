package de.raindancer.modules.arena.util;

import de.raindancer.core.ui.choose.PlayerDirectory;
import de.raindancer.core.ui.choose.PlayerEntry;
import org.bukkit.Server;

/** Who is on this server now, for Core's player chooser — asking somebody to a fight only makes sense for them. */
public final class OnlinePlayers {

    private OnlinePlayers() {
    }

    /** Everybody here, and everybody on the survival server of the link — whom a challenge can reach. */
    @SuppressWarnings("deprecation") // a name from the other server is all there is; its id is only for the head
    public static PlayerDirectory reachable(Server server, java.util.function.Supplier<java.util.Set<String>> elsewhere) {
        return new PlayerDirectory(() -> {
            java.util.List<PlayerEntry> people = new java.util.ArrayList<>();
            java.util.Set<String> here = new java.util.HashSet<>();
            long now = System.currentTimeMillis();
            server.getOnlinePlayers().forEach(player -> {
                here.add(player.getName().toLowerCase(java.util.Locale.ROOT));
                people.add(new PlayerEntry(player.getUniqueId(), player.getName(), true, now));
            });
            elsewhere.get().stream().filter(name -> !here.contains(name.toLowerCase(java.util.Locale.ROOT)))
                    .forEach(name -> people.add(new PlayerEntry(server.getOfflinePlayer(name).getUniqueId(), name, true, now)));
            return people;
        }, System::currentTimeMillis);
    }

    public static PlayerDirectory of(Server server) {
        return new PlayerDirectory(() -> server.getOnlinePlayers().stream()
                .map(player -> new PlayerEntry(player.getUniqueId(), player.getName(), true, System.currentTimeMillis()))
                .toList(), System::currentTimeMillis);
    }
}
