package de.raindancer.modules.hungergames.listener;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.service.LobbyBoxService;
import de.raindancer.modules.hungergames.store.GameSession;
import de.raindancer.modules.hungergames.util.PermissionNodes;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;

import java.util.Locale;
import java.util.UUID;

/**
 * The glass box above the arena: who gets put in it, who may be on the server at all, and that nobody fights
 * inside it.
 *
 * <h2>Three rules, and why they belong together</h2>
 * <ul>
 *   <li><b>Before {@code /init}, almost nobody may join.</b> {@code game.pre-init-admins} is the short list
 *       of people who may be on the server while the arena does not exist yet. Without it, forty people who
 *       heard the server was up land at spawn in the middle of an admin trying to pick a spot to build an
 *       arena in — and the arena then gets built around them.</li>
 *   <li><b>A tribute who joins during the wait is put in the box.</b> Not moved every tick, and not held
 *       there: relocated once, when they arrive somewhere they should not be.</li>
 *   <li><b>Nobody fights in the box.</b> Tributes are stood next to each other with nothing to do for half
 *       an hour, which is the only ingredient a fight has ever needed.</li>
 * </ul>
 *
 * <p>All three are the same fact from three angles — the box exists and it is the waiting room — and
 * splitting them across three classes is how the source ended up cancelling combat in a lobby that had
 * already been demolished.
 *
 * <h2>Where the deciding happens</h2>
 * Nowhere here. {@link LobbyBoxService} answers whether the box is up, whether a position is inside it and
 * whether a hit must be cancelled; this class converts a Bukkit event into that question and acts on the
 * answer. That is what lets the containment arithmetic be tested without a server — see
 * {@code LobbyBoxServiceTest} — and it is the seam the source did not have.
 */
public final class LobbyListener implements IHungerGamesListener {

    private final GameSession session;
    private final LobbyBoxService lobby;
    private final Messages messages;

    private volatile HungerGamesSettings settings;

    public LobbyListener(GameSession session, LobbyBoxService lobby, Messages messages,
                         HungerGamesSettings settings) {
        this.session = session;
        this.lobby = lobby;
        this.messages = messages;
        this.settings = settings;
    }

    public void settings(HungerGamesSettings settings) {
        this.settings = settings;
    }

    @Override
    public void forget(UUID player) {
        // Nothing is remembered. Every decision here is read from the session and the box at the moment it
        // is asked, which is what makes a rejoin behave the same as a first join.
    }

    /**
     * The door, before an arena exists.
     *
     * <p>{@link PlayerLoginEvent} rather than the join event: this is a refusal, and a refusal has to happen
     * before the player is in the world. Refusing at join means kicking somebody who has already loaded in,
     * which fires their spawn, their chunk loads and every other plugin's join handler first.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onLogin(PlayerLoginEvent event) {
        if (session.phase().isInitialized()) {
            return;   // there is an arena; the tournament's own rules take over from here
        }
        Player player = event.getPlayer();
        if (mayBeHereBeforeTheArenaExists(player)) {
            return;
        }
        event.disallow(PlayerLoginEvent.Result.KICK_OTHER,
                messages.get("hungergames.not-open-yet"));
    }

    /**
     * Whether somebody may be on the server before {@code /init}.
     *
     * <p>Three ways in, and the permission is the important one: the name list is what a server owner writes
     * in a config file the afternoon before, and it goes stale the moment somebody changes their name.
     * Anybody holding the admin node, and any operator, is let through regardless — locking the person who
     * has to run {@code /init} out of their own server because their name was spelled differently in a YAML
     * file is the failure this whole check is supposed to prevent.
     */
    private boolean mayBeHereBeforeTheArenaExists(Player player) {
        if (player.isOp() || player.hasPermission(PermissionNodes.ADMIN)
                || player.hasPermission(PermissionNodes.GAMEMASTER)) {
            return true;
        }
        String name = player.getName().toLowerCase(Locale.ROOT);
        return settings.preInitAdmins().stream()
                .anyMatch(allowed -> allowed != null && allowed.toLowerCase(Locale.ROOT).equals(name));
    }

    /**
     * A tribute arriving while the box is up.
     *
     * <p>Runs at {@link EventPriority#MONITOR} — after {@code ConnectionListener} has refreshed their name
     * and after any other plugin that wants a say in where somebody spawns. Moving them first and letting a
     * spawn plugin move them back afterwards is the bug this ordering exists to avoid.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!lobby.shouldRelocateOnJoin(player.getUniqueId(), pointOf(player))) {
            return;
        }
        lobby.lobbyCentre().ifPresent(centre -> {
            var world = player.getServer().getWorld(centre.worldName());
            if (world == null) {
                return;
            }
            player.teleport(new Location(world, centre.x(), centre.y(), centre.z(),
                    player.getLocation().getYaw(), 0f));
            // Adventure, not survival: the box is glass and a tribute with a pickaxe and nothing to do for
            // half an hour will find out how thick it is.
            player.setGameMode(GameMode.ADVENTURE);
            messages.send(player, "hungergames.waiting-in-the-lobby");
        });
    }

    /**
     * A hit landing in or from the box.
     *
     * <p>Cancelled outright rather than merely reduced. The half-hour before a round is when people are
     * organising teams, and a tournament that starts with somebody already on four hearts because a
     * team-mate was bored is not the round anybody signed up for.
     *
     * <p>Both ends are checked. Cancelling only when the <em>victim</em> is inside would leave a tribute
     * standing in the doorway able to hit somebody outside it, which is the same fight with an extra step.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (session.phase() == GamePhase.RUNNING) {
            return;   // the fastest possible exit for the phase this fires most in
        }
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = attackerOf(event);
        if (attacker == null) {
            return;
        }
        if (lobby.forbidsCombatBetween(pointOf(attacker), pointOf(victim))) {
            event.setCancelled(true);
            messages.send(attacker, "hungergames.no-fighting-yet");
        }
    }

    /**
     * Whoever threw the punch, arrow or trident.
     *
     * <p>{@code getDamager()} is the projectile, not the person who fired it — a check written against the
     * damager alone lets an entire bow fight happen in the lobby.
     */
    private static Player attackerOf(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player direct) {
            return direct;
        }
        if (event.getDamager() instanceof org.bukkit.entity.Projectile projectile
                && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    private static LobbyBoxService.Point pointOf(Player player) {
        Location where = player.getLocation();
        String world = where.getWorld() == null ? "" : where.getWorld().getName();
        return new LobbyBoxService.Point(world, where.getX(), where.getY(), where.getZ());
    }

    @Override
    public String describe() {
        return "the glass lobby: who is let in, who is put inside, and that nobody fights in it";
    }
}
