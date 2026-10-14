package de.raindancer.modules.cosmetics.service;

import de.raindancer.core.moderation.audit.Audit;
import de.raindancer.core.moderation.audit.AuditEntry;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.cosmetics.CosmeticsSettings;
import de.raindancer.modules.cosmetics.model.ClearScope;
import de.raindancer.modules.cosmetics.rules.ClearRule;
import de.raindancer.modules.cosmetics.store.ClearNetwork;
import de.raindancer.modules.cosmetics.util.PermissionNodes;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Taking cosmetics off — your own, or, for staff, somebody else's.
 *
 * <p>An online target is handled on their own thread, because their particle lives in their persistent
 * data and on Folia only that thread may touch it; the scheduling is injected so a test needs no server.
 * A target on another server of the network is asked over {@link ClearNetwork}: that server clears their
 * particle. A target who is nowhere can only lose their name style, which is Core's and stored by id.
 */
public final class ClearService implements ICosmeticsService {

    private final NameStyleService names;
    private final ParticleService particles;
    private final Messages messages;
    private final Audit audit;
    private final BiConsumer<Player, Runnable> onOwnThread;
    private final ClearRule rule = new ClearRule();
    private volatile ClearNetwork network;

    public ClearService(NameStyleService names, ParticleService particles, Messages messages, Audit audit,
                        BiConsumer<Player, Runnable> onOwnThread, CosmeticsSettings settings) {
        this.names = names;
        this.particles = particles;
        this.messages = messages;
        this.audit = audit;
        this.onOwnThread = onOwnThread;
        settings(settings);
    }

    @Override
    public void settings(CosmeticsSettings fresh) {
        // Nothing here reads the file; the rule is the whole policy.
    }

    /** Whether this sender could clear this target's cosmetics at all — what a menu greys a button by. */
    public boolean may(CommandSender actor, boolean self) {
        return actor.hasPermission(self ? PermissionNodes.CLEAR : PermissionNodes.CLEAR_OTHERS);
    }

    /**
     * Joins the network: answers other servers asking to clear somebody here, and asks them for somebody there.
     *
     * @param localPlayer the player of this server with that id, if any
     */
    public void connectNetwork(ClearNetwork joined, Function<UUID, Player> localPlayer) {
        this.network = joined;
        joined.answer((player, scope) -> {
            Player here = localPlayer.apply(player);
            if (here == null) {
                return CompletableFuture.completedFuture(new ClearNetwork.Result(false, false));
            }
            CompletableFuture<ClearNetwork.Result> done = new CompletableFuture<>();
            onOwnThread.accept(here, () -> {
                boolean had = !particles.current(here).isNone() || !particles.wings(here).isNone();
                if (had && scope.includesParticles()) {
                    particles.takeOff(here, false);
                    particles.takeOffWings(here);
                }
                done.complete(new ClearNetwork.Result(true, had));
            });
            return done;
        });
    }

    public void clear(CommandSender actor, OfflinePlayer target, ClearScope scope) {
        Player online = target.getPlayer();
        if (online == null) {
            Optional<String> elsewhere = scope.includesParticles() ? serverOf(target.getUniqueId()) : Optional.empty();
            if (elsewhere.isPresent() && mayAsk(actor, target, scope)) {
                askThere(actor, target, scope, elsewhere.get());
            } else {
                run(actor, target, null, scope, null);
            }
            return;
        }
        onOwnThread.accept(online, () -> run(actor, target, online, scope, null));
    }

    private Optional<String> serverOf(UUID player) {
        ClearNetwork joined = network;
        return joined == null ? Optional.empty() : joined.serverOf(player);
    }

    /** Whether the actor's permissions would let this through — what is checked before another server is troubled. */
    private boolean mayAsk(CommandSender actor, OfflinePlayer target, ClearScope scope) {
        boolean self = actor instanceof Player who && who.getUniqueId().equals(target.getUniqueId());
        return rule.judge(new ClearRule.Request(scope, self, actor.hasPermission(PermissionNodes.CLEAR),
                actor.hasPermission(PermissionNodes.CLEAR_OTHERS), true, true, true)).isAllowed();
    }

    private void askThere(CommandSender actor, OfflinePlayer target, ClearScope scope, String server) {
        network.ask(server, target.getUniqueId(), scope).thenAccept(answer -> {
            ClearNetwork.Result result = answer.filter(ClearNetwork.Result::found).orElse(null);
            Runnable finish = () -> run(actor, target, null, scope, result);
            if (actor instanceof Player who) {
                onOwnThread.accept(who, finish);
            } else {
                finish.run();
            }
        });
    }

    private void run(CommandSender actor, OfflinePlayer target, Player online, ClearScope scope,
                     ClearNetwork.Result remote) {
        boolean self = actor instanceof Player who && who.getUniqueId().equals(target.getUniqueId());
        String shown = PlayerTargets.shownName(target);
        ClearRule.Request request = new ClearRule.Request(scope, self,
                actor.hasPermission(PermissionNodes.CLEAR), actor.hasPermission(PermissionNodes.CLEAR_OTHERS),
                online != null || remote != null, !names.current(target.getUniqueId()).isEmpty(),
                online != null ? !particles.current(online).isNone() : remote != null && remote.hadParticles());
        Verdict verdict = rule.judge(request);
        if (verdict.isRefused()) {
            messages.send(actor, verdict.reason(), "player", shown);
            return;
        }
        ClearRule.Plan plan = rule.plan(request);
        if (plan.name()) {
            names.strip(target.getUniqueId());
        }
        if (plan.particles() && online != null) {
            particles.takeOff(online, false);
            particles.takeOffWings(online);
        }
        String what = plan.name() && plan.particles() ? "name style and particle"
                : plan.name() ? "name style" : "particle";
        if (self) {
            messages.send(actor, "cosmetics.clear.done", "what", what);
        } else {
            messages.send(actor, "cosmetics.clear.done-other", "player", shown, "what", what);
            audit(actor, target, what);
            if (online != null) {
                messages.send(online, "cosmetics.clear.by-staff", "what", what);
            } else if (remote != null) {
                network.tell(target.getUniqueId(), "cosmetics.clear.by-staff", "what", what);
            }
        }
        if (plan.particlesOutOfReach()) {
            messages.send(actor, "cosmetics.clear.offline-particles-note", "player", shown);
        }
    }

    private void audit(CommandSender actor, OfflinePlayer target, String what) {
        AuditEntry.Builder entry = AuditEntry.of("cosmetics", "cleared somebody's cosmetics")
                .to(target.getUniqueId(), target.getName() == null ? target.getUniqueId().toString() : target.getName())
                .saying(what)
                .with("cleared", what);
        if (actor instanceof Player who) {
            entry.by(who.getUniqueId(), who.getName());
        }
        audit.record(entry);
    }

    @Override
    public String describe() {
        return "taking cosmetics off, yours or somebody else's";
    }
}
