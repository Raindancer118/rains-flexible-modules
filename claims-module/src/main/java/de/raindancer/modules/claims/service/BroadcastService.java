package de.raindancer.modules.claims.service;

import de.raindancer.modules.claims.ClaimSettings;
import de.raindancer.modules.claims.model.BroadcastScope;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimFeature;
import de.raindancer.modules.claims.model.ClaimPoint;
import de.raindancer.modules.claims.model.ClaimNames;
import de.raindancer.modules.claims.rules.FeatureRules;
import de.raindancer.core.ui.messages.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Announces the things that happen to people in claims — somebody escorted out, banned, timed out, let
 * back in.
 * <p>
 * Separate from the messages the parties already get: the owner is told their kick worked and the target
 * is told they were kicked, whatever the settings say. This is the audience beyond those two, and it is
 * entirely optional because on a big server it is noise and on a small one it is half the fun.
 * <p>
 * Every announcement is a random pick from the wordings in messages.yml, so the same thing happening
 * twice does not read identically.
 */
public final class BroadcastService implements IClaimService {

    @Override
    public String describe() {
        return "telling the server when somebody is thrown out";
    }


    private final Plugin plugin;
    /** A snapshot, replaced on reload — see settings(ClaimSettings). */
    private volatile ClaimSettings settings;
    private final FeatureRules features;
    private final Messages messages;
    private final ClaimNames claimNames;

    public BroadcastService(Plugin plugin, ClaimSettings settings, FeatureRules features,
                            Messages messages, ClaimNames claimNames) {
        this.plugin = plugin;
        this.settings = settings;
        this.features = features;
        this.messages = messages;
        this.claimNames = claimNames;
    }

    /**
     * Swaps in the settings as they are now.
     *
     * <p>Called on reload. The field is a snapshot rather than a live view, so nothing here has to think about a
     * value changing halfway through a calculation — and replacing the whole snapshot means a reload takes effect
     * on the next event rather than on the next restart.
     */
    public void settings(ClaimSettings settings) {
        this.settings = settings;
    }

    /**
     * The claim placeholders every announcement gets.
     * <p>
     * {@code <claim>} is the possessive form — "Raindancer118's home" — because that is what reads well
     * in a sentence and because "thrown out of home" says nothing about whose doorstep it was.
     * {@code <claim-name>} and {@code <owner>} are offered alongside it so a server that wants the
     * wording arranged differently can do that in messages.yml without losing anything.
     */
    private Map<String, String> claimPlaceholders(Claim claim, String target, String by) {
        Map<String, String> placeholders = new java.util.HashMap<>();
        placeholders.put("player", target);
        placeholders.put("by", by);
        placeholders.put("claim", claimNames.possessive(claim));
        placeholders.put("claim-name", claim.name());
        placeholders.put("owner", claimNames.primaryOwner(claim));
        placeholders.put("owners", claimNames.allOwners(claim));
        return placeholders;
    }

    /** Somebody was escorted out of a claim. */
    public void kicked(Claim claim, String target, String by) {
        if (!settings.broadcastKick()) {
            return;
        }
        announce(claim, "broadcast.kicked", claimPlaceholders(claim, target, by), target);
    }

    /** Somebody was banned from a claim for good. */
    public void banned(Claim claim, String target, String by, String reason) {
        if (!settings.broadcastBan()) {
            return;
        }
        Map<String, String> placeholders = claimPlaceholders(claim, target, by);
        placeholders.put("reason", reason == null || reason.isBlank() ? "no reason given" : reason);
        announce(claim, "broadcast.banned", placeholders, target);
    }

    /** Somebody was barred from a claim for a while. */
    public void timedOut(Claim claim, String target, String by, String duration) {
        if (!settings.broadcastTimeout()) {
            return;
        }
        Map<String, String> placeholders = claimPlaceholders(claim, target, by);
        placeholders.put("duration", duration);
        announce(claim, "broadcast.timed-out", placeholders, target);
    }

    /** Somebody is welcome again. */
    public void lifted(Claim claim, String target, String by) {
        if (!settings.broadcastLift()) {
            return;
        }
        announce(claim, "broadcast.lifted", claimPlaceholders(claim, target, by), target);
    }

    /**
     * Sends one wording to whoever the scope says should read it.
     * <p>
     * The person it happened to is always included when they are online, whatever the scope: an
     * announcement about somebody that they cannot see would be a strange thing to arrange.
     */
    private void announce(Claim claim, String key, Map<String, String> placeholders, String target) {
        // A server that took announcements away gets no announcements, however they were triggered.
        if (!features.isOffered(ClaimFeature.BROADCASTS)) {
            return;
        }
        // Flattened, not handed over whole: Messages takes name, value, name, value, and a map
        // passed straight in is one silent argument that substitutes nothing. See Placeholders.
        Component message = messages.variant(key,
                de.raindancer.modules.claims.util.Placeholders.of(placeholders));
        for (Player recipient : audience(claim, target)) {
            recipient.sendMessage(message);
        }
    }

    private List<Player> audience(Claim claim, String target) {
        BroadcastScope scope = settings.broadcastScope();
        List<Player> recipients = new ArrayList<>();
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online.getName().equalsIgnoreCase(target) || inScope(scope, claim, online)) {
                recipients.add(online);
            }
        }
        return recipients;
    }

    private boolean inScope(BroadcastScope scope, Claim claim, Player player) {
        return switch (scope) {
            case EVERYONE -> true;
            case CLAIM -> belongsTo(claim, player.getUniqueId());
            case NEARBY -> isNear(claim, player);
        };
    }

    private static boolean belongsTo(Claim claim, UUID uuid) {
        return claim.isOwner(uuid) || claim.members().containsKey(uuid);
    }

    /**
     * Whether the player is close enough to the claim to have plausibly seen it happen.
     * <p>
     * Measured from the claim's centre on the horizontal plane: a claim is a volume rather than a point,
     * so an exact distance to its nearest edge would be a lot of work for a decision about who reads a
     * chat line.
     */
    private boolean isNear(Claim claim, Player player) {
        Location location = player.getLocation();
        World world = location.getWorld();
        if (world == null || !world.getUID().equals(claim.worldId())) {
            return false;
        }
        ClaimPoint centre = claim.shape().centre();
        double dx = location.getX() - (centre.x() + 0.5D);
        double dz = location.getZ() - (centre.z() + 0.5D);
        double radius = settings.broadcastNearbyRadius();
        return dx * dx + dz * dz <= radius * radius;
    }
}
