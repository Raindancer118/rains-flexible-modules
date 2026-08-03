package de.raindancer.modules.claims;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.protection.Land;
import de.raindancer.core.world.protection.LandAction;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entry tolls: works out whether a visitor owes something, asks them, charges them and banks the
 * proceeds for the owners.
 * <p>
 * Nobody is ever charged without confirming first, and the confirmation path is shared between walking
 * in and teleporting in, so third party TPA plugins are covered for free — they all go through
 * {@code PlayerTeleportEvent}.
 */
public final class EntryFeeService {

    /** A paid entry that is still valid. */
    private record Pass(UUID claimId, long expiresAt) {
        boolean valid(UUID claim) {
            return claimId.equals(claim) && (expiresAt == 0L || System.currentTimeMillis() < expiresAt);
        }
    }

    /** An outstanding question to a player. */
    public record Prompt(UUID claimId, long expiresAt, Location destination, boolean teleport) {
        public boolean expired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    private final Plugin plugin;
    /** A snapshot, replaced on reload — see settings(ClaimSettings). */
    private volatile ClaimSettings settings;
    private final Messages messages;
    private final CostService costs;
    private final ClaimService claims;
    private final Land protection;
    private final Features features;

    private final Map<UUID, Pass> passes = new ConcurrentHashMap<>();
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();
    /** player → claim → epoch millis until which we stay quiet after a decline. */
    private final Map<UUID, Map<UUID, Long>> declineCooldowns = new ConcurrentHashMap<>();

    public EntryFeeService(Plugin plugin, ClaimSettings settings, Messages messages, CostService costs,
                           ClaimService claims, Land protection, Features features) {
        this.features = features;
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
        this.costs = costs;
        this.claims = claims;
        this.protection = protection;
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

    /** Whether the player still owes the toll for this claim. */
    public boolean requiresPayment(Claim claim, Player player) {
        if (!features.isOffered(ClaimFeature.ENTRY_FEE)) {
            return false;
        }
        EntryFee fee = claim.entryFee();
        if (!fee.enabled()) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        // Being an operator is not an exemption by itself: staff who genuinely need to walk in free
        // either hold rec.admin.nofee, switch the protection bypass on for as long as they need it, or
        // the server has said outright that staff do not pay.
        if (claim.isOwner(uuid) || player.hasPermission("rec.admin.nofee")
                || protection.isBypassing(player)
                || (settings.entryFeeExemptAdmins() && player.hasPermission("rec.admin"))) {
            return false;
        }
        if (settings.entryFeeExemptTrusted() && claim.members().containsKey(uuid)) {
            return false;
        }
        Pass pass = passes.get(uuid);
        return pass == null || !pass.valid(claim.id());
    }

    public boolean onDeclineCooldown(Player player, Claim claim) {
        Map<UUID, Long> perClaim = declineCooldowns.get(player.getUniqueId());
        if (perClaim == null) {
            return false;
        }
        Long until = perClaim.get(claim.id());
        return until != null && System.currentTimeMillis() < until;
    }

    /** Sends the offer. Returns false when a prompt was suppressed (cooldown or duplicate). */
    public boolean offer(Player player, Claim claim, Location destination, boolean teleport) {
        if (onDeclineCooldown(player, claim)) {
            return false;
        }
        Prompt existing = prompts.get(player.getUniqueId());
        if (existing != null && !existing.expired() && existing.claimId().equals(claim.id())) {
            return false;
        }
        EntryFee fee = claim.entryFee();
        long timeout = settings.entryFeePromptTimeoutSeconds() * 1000L;
        prompts.put(player.getUniqueId(),
                new Prompt(claim.id(), System.currentTimeMillis() + timeout,
                        destination == null ? null : destination.clone(), teleport));

        Component price = costs.describe(fee.type(), fee.amount(), fee.item());
        player.sendMessage(messages.prefixed("entry-fee.offer", 
                "claim", claim.name(),
                "price", net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(price),
                "seconds", String.valueOf(settings.entryFeePromptTimeoutSeconds())));
        player.sendMessage(Component.text("   ")
                .append(button("entry-fee.accept-button", "/claim accept", NamedTextColor.GREEN))
                .append(Component.text("   "))
                .append(button("entry-fee.decline-button", "/claim decline", NamedTextColor.RED)));
        return true;
    }

    private Component button(String messageKey, String command, NamedTextColor color) {
        return messages.get(messageKey).colorIfAbsent(color)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(command, NamedTextColor.GRAY)));
    }

    public Optional<Prompt> pendingPrompt(Player player) {
        Prompt prompt = prompts.get(player.getUniqueId());
        if (prompt == null) {
            return Optional.empty();
        }
        if (prompt.expired()) {
            prompts.remove(player.getUniqueId());
            return Optional.empty();
        }
        return Optional.of(prompt);
    }

    /** Charges the toll and grants an entry pass. */
    public boolean accept(Player player) {
        Optional<Prompt> pending = pendingPrompt(player);
        if (pending.isEmpty()) {
            messages.send(player, "entry-fee.nothing-pending");
            return false;
        }
        Prompt prompt = pending.get();
        Optional<Claim> maybeClaim = claims.registry().byId(prompt.claimId());
        if (maybeClaim.isEmpty()) {
            prompts.remove(player.getUniqueId());
            messages.send(player, "entry-fee.claim-gone");
            return false;
        }
        Claim claim = maybeClaim.get();
        EntryFee fee = claim.entryFee();
        if (!fee.enabled() || !features.isOffered(ClaimFeature.ENTRY_FEE)) {
            // The owner switched the toll off while the prompt was open — let them in for free.
            prompts.remove(player.getUniqueId());
            grantPass(player, claim);
            resume(player, prompt);
            return true;
        }

        CostService.Charge charge = costs.charge(player, fee.type(), fee.amount(), fee.item());
        if (!charge.success()) {
            messages.send(player, "entry-fee.cannot-afford", 
                    "claim", claim.name(), "missing", charge.shortfallDescription());
            return false;
        }

        depositToBank(claim, fee);
        claims.saveAsync(claim);

        prompts.remove(player.getUniqueId());
        grantPass(player, claim);
        messages.send(player, "entry-fee.paid", 
                "claim", claim.name(),
                "price", net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(costs.describe(fee.type(), fee.amount(), fee.item())));
        notifyOwners(claim, player);
        resume(player, prompt);
        return true;
    }

    private void depositToBank(Claim claim, EntryFee fee) {
        switch (fee.type()) {
            case ITEM -> {
                ItemStack stack = fee.item();
                if (stack != null) {
                    stack.setAmount(fee.amount());
                    claim.bank().depositItem(stack);
                }
            }
            case XP_LEVELS -> claim.bank()
                    .depositExperience(CostService.totalExperienceForLevel(fee.amount()));
            case XP_POINTS -> claim.bank().depositExperience(fee.amount());
            case NONE -> {
            }
        }
        claim.markDirty();
    }

    private void notifyOwners(Claim claim, Player payer) {
        for (UUID owner : claim.owners()) {
            Player online = plugin.getServer().getPlayer(owner);
            if (online != null) {
                messages.send(online, "entry-fee.owner-notified", 
                        "player", payer.getName(), "claim", claim.name());
            }
        }
    }

    private void grantPass(Player player, Claim claim) {
        int seconds = claim.entryFee().passDurationSeconds();
        long expiry = seconds <= 0 ? System.currentTimeMillis() + 3_000L : System.currentTimeMillis() + seconds * 1000L;
        passes.put(player.getUniqueId(), new Pass(claim.id(), expiry));
    }

    /** Carries the player to where they were heading before the prompt interrupted them. */
    private void resume(Player player, Prompt prompt) {
        Location destination = prompt.destination();
        if (destination == null) {
            return;
        }
        Scheduling.entityLater(plugin, player, 2L, () -> {
            if (player.isOnline()) {
                player.teleportAsync(destination);
            }
        });
    }

    public void decline(Player player) {
        Optional<Prompt> pending = pendingPrompt(player);
        if (pending.isEmpty()) {
            messages.send(player, "entry-fee.nothing-pending");
            return;
        }
        Prompt prompt = pending.get();
        prompts.remove(player.getUniqueId());
        declineCooldowns.computeIfAbsent(player.getUniqueId(), key -> new ConcurrentHashMap<>())
                .put(prompt.claimId(),
                        System.currentTimeMillis() + settings.entryFeeDeclineCooldownSeconds() * 1000L);
        messages.send(player, "entry-fee.declined");
    }

    /** Cheap sanity check used before offering: is the fee even payable right now? */
    public boolean canAfford(Player player, Claim claim) {
        EntryFee fee = claim.entryFee();
        return costs.canAfford(player, fee.type(), fee.amount(), fee.item());
    }

    public Component describe(Claim claim) {
        EntryFee fee = claim.entryFee();
        if (!fee.enabled()) {
            return Component.text("none");
        }
        return costs.describe(fee.type(), fee.amount(), fee.item());
    }

    /** True when the visitor is allowed past the gate — either nothing is owed or a pass exists. */
    public boolean mayPass(Claim claim, Player player) {
        return !requiresPayment(claim, player);
    }

    public void forget(UUID uuid) {
        passes.remove(uuid);
        prompts.remove(uuid);
        declineCooldowns.remove(uuid);
    }

    /** Drops stale passes and prompts; scheduled periodically so the maps cannot grow without bound. */
    public void prune() {
        long now = System.currentTimeMillis();
        passes.entrySet().removeIf(entry -> entry.getValue().expiresAt() != 0L && entry.getValue().expiresAt() < now);
        prompts.entrySet().removeIf(entry -> entry.getValue().expired());
        declineCooldowns.values().forEach(map -> map.entrySet().removeIf(entry -> entry.getValue() < now));
        declineCooldowns.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    /** Fee currencies an owner may pick; the item option needs a configured item. */
    public boolean isUsableType(CostType type) {
        return type != CostType.NONE;
    }

    public boolean entryBlockedByPermission(Claim claim, Player player, Land protection) {
        return !protection.has(claim.area(), player, LandAction.ENTER);
    }
}
