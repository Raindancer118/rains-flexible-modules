package de.raindancer.modules.manhunt.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.service.ManhuntLives.Verdict;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What actually happens when somebody dies mid-hunt: a Runner spends a life or leaves the hunt for
 * good, and a Hunter serves whatever delay the owner set before being let back in.
 *
 * <h2>Where the deciding happens</h2>
 * {@link ManhuntLives}, as ever — this class converts a {@link PlayerDeathEvent} into one
 * {@code record} call and acts on the verdict. The hunt ending because the last Runner is out is
 * {@code AllRunnersDeadEndCondition}'s to notice, off the same event, which is why the recording here
 * runs at {@code HIGH}: the board must already be written when that condition looks at it a tick
 * later.
 *
 * <h2>Why Spectator is applied on respawn, not on death</h2>
 * A dead player is not in a game mode yet — they are on the death screen, and a mode set there is
 * overwritten the moment they click through it. {@link PlayerRespawnEvent} is the first moment the
 * change sticks, so both the eliminated Runner and the waiting Hunter are marked here and dealt with
 * there.
 */
public final class ManhuntDeathListener implements Listener {

    private final Plugin plugin;
    private final ManhuntService manhunt;
    private final ManhuntLives lives;
    private final Messages messages;

    /** Whoever must land in Spectator on their next respawn, and why. */
    private final Set<UUID> eliminated = ConcurrentHashMap.newKeySet();
    private final Set<UUID> serving = ConcurrentHashMap.newKeySet();

    private volatile ManhuntSettings settings;

    public ManhuntDeathListener(Plugin plugin, ManhuntService manhunt, ManhuntLives lives,
                               Messages messages, ManhuntSettings settings) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.manhunt = Objects.requireNonNull(manhunt, "manhunt");
        this.lives = Objects.requireNonNull(lives, "lives");
        this.messages = messages;
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** Told the live settings whenever they change — wired via {@code SettingsStore.onChange}. */
    public void settings(ManhuntSettings fresh) {
        this.settings = fresh;
    }

    /** A fresh hunt: nobody is out, nobody is serving a delay. */
    public void reset() {
        eliminated.clear();
        serving.clear();
    }

    /** Whether this player is out of the hunt and only watching — for the screens and the narration. */
    public boolean isOut(UUID player) {
        return eliminated.contains(player);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        if (!manhunt.isRunning()) {
            return;
        }
        Player player = event.getEntity();
        UUID id = player.getUniqueId();
        if (manhunt.teams().isRunner(id)) {
            onRunnerDeath(player, id);
            return;
        }
        if (manhunt.teams().isHunter(id) && settings.hunterRespawnDelaySecondsClamped() > 0) {
            serving.add(id);
        }
    }

    private void onRunnerDeath(Player player, UUID id) {
        Verdict verdict = lives.record(id);
        if (verdict == Verdict.RESPAWNED) {
            say(player, "manhunt.death.lives-left", "lives", String.valueOf(lives.livesLeft(id)));
            return;
        }
        eliminated.add(id);
        if (settings.eliminatedSpectate()) {
            say(player, "manhunt.death.eliminated-watching");
        } else {
            say(player, "manhunt.death.eliminated");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (eliminated.contains(id)) {
            if (settings.eliminatedSpectate()) {
                // A tick later, for the same reason the tracker's replacement compass waits: the
                // server is still putting the respawned player together around this event.
                Scheduling.entityLater(plugin, player, 1L, () -> player.setGameMode(GameMode.SPECTATOR));
            }
            return;
        }
        if (!serving.remove(id)) {
            return;
        }
        int seconds = settings.hunterRespawnDelaySecondsClamped();
        Scheduling.entityLater(plugin, player, 1L, () -> {
            player.setGameMode(GameMode.SPECTATOR);
            say(player, "manhunt.death.hunter-waiting", "seconds", String.valueOf(seconds));
        });
        Scheduling.entityLater(plugin, player, 1L + seconds * 20L, () -> {
            // Only if the hunt they were held for is still the hunt going on: a delay that outlives
            // its own run must not drop somebody into Survival in the middle of the next one's lobby.
            if (player.getGameMode() == GameMode.SPECTATOR && !eliminated.contains(id)) {
                player.setGameMode(GameMode.SURVIVAL);
                say(player, "manhunt.death.hunter-back");
            }
        });
    }

    private void say(Player player, String key, String... values) {
        if (messages != null) {
            messages.send(player, key, (Object[]) values);
        }
    }

    public String describe() {
        return "what a death costs: a Runner's lives, and a Hunter's wait before coming back";
    }
}
