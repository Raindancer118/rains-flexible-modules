package de.raindancer.modules.hungergames.service;

import de.raindancer.core.content.items.CustomItem;
import de.raindancer.core.content.items.ItemFactory;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.model.TokenSchedule;
import de.raindancer.modules.hungergames.store.GameSession;
import de.raindancer.modules.hungergames.store.RuntimeStore;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sponsor tokens: a physical item, handed out to living tributes over time for surviving, and spent at a
 * {@link SponsorBeaconService sponsor beacon}.
 *
 * <h2>Why a token is a {@link CustomItem}, not a material and a hand-rolled {@code PersistentDataContainer}
 * key</h2>
 * The source engine minted its own {@code NamespacedKey(plugin, "sponsor_token")} and matched on it — the
 * one thing {@code ReuseTest} exists to catch, because Core's item registry already owns exactly that
 * identity question for every custom item on this server. A token is defined once, under this module's own
 * id, and {@link #isToken} asks Core's {@link ItemFactory} rather than carrying a second, private key that
 * could disagree with the registry's about what counts as a real token.
 *
 * <h2>Where the schedule's own numbers come from</h2>
 * {@code HungerGamesSettings} has no {@code sponsors} topic yet — see {@code MODULE-LAYOUT.md}'s note on
 * what still has no settings home. Until it does, {@link #tick} takes the wave shape ({@code firstAfter},
 * {@code interval}, the amount per wave and the optional cap) as parameters rather than reading a settings
 * key that does not exist, so nothing here quietly reads zero for a value nobody has been able to configure
 * yet. Whoever wires this in supplies real numbers today and a settings-backed supplier once the topic
 * lands; either way {@link TokenSchedule}'s own arithmetic — which is what decides how many tokens a wave
 * owes — never has to change.
 */
public final class SponsorTokenService implements IHungerGamesService {

    /** One tribute's progress: how many waves they were already paid for, and their running total. */
    private record Progress(int wavesReceived, int tokensEarned) {
        static final Progress NONE = new Progress(0, 0);
    }

    /** The wave shape a caller currently wants applied — see the class note on where the numbers come from. */
    public record Schedule(Duration firstAfter, Duration interval, int amountPerWave, int maxPerPlayer,
                            boolean onlyAlive) {
    }

    /** Handing an item stack to a player, dropping whatever does not fit — Bukkit's inventory, not this class's. */
    @FunctionalInterface
    public interface Give {
        void give(Player player, ItemStack tokens);
    }

    @FunctionalInterface
    public interface RoundLog {
        void log(String category, String message);
    }

    private final GameSession session;
    private final ItemFactory items;
    private final CustomItem tokenItem;
    private final Give give;
    private final AnnouncementService announcements;
    private final RoundLog roundLog;
    private final RuntimeStore runtimeStore;

    private final Map<UUID, Progress> perPlayer = new LinkedHashMap<>();

    private HungerGamesSettings settings = HungerGamesSettings.DEFAULTS;

    public SponsorTokenService(GameSession session, ItemFactory items, CustomItem tokenItem, Give give,
                                AnnouncementService announcements, RoundLog roundLog,
                                RuntimeStore runtimeStore) {
        this.session = session;
        this.items = items;
        this.tokenItem = tokenItem;
        this.give = give;
        this.announcements = announcements;
        this.roundLog = roundLog;
        this.runtimeStore = runtimeStore;
    }

    @Override
    public void settings(HungerGamesSettings settings) {
        this.settings = settings;
    }

    // ==================== lifecycle ====================

    public void start() {
        perPlayer.clear();
        runtimeStore.loadTokenState().forEach((uuid, state) ->
                perPlayer.put(uuid, new Progress(state.wavesReceived(), state.tokensEarned())));
    }

    public boolean tokensEnabled() {
        // Sponsors as a whole are not yet a settings switch of their own — see the class note — so a
        // caller may still say no by handing in a Schedule with a zero amount, which pendingTokens already
        // treats as "nothing owed" rather than this service inventing a second on/off switch.
        return true;
    }

    // ==================== the item ====================

    /** Whether an item stack is a real sponsor token — Core's registry decides, not a material check. */
    public boolean isToken(ItemStack stack) {
        return stack != null && items.is(stack, tokenItem.key());
    }

    /** Tokens actually in a player's inventory right now. */
    public int countTokens(Player player) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (isToken(stack)) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    /** @return {@code false} when there were not enough to remove — nothing is taken in that case */
    public boolean removeTokens(Player player, int amount) {
        if (countTokens(player) < amount) {
            return false;
        }
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack stack = contents[i];
            if (!isToken(stack)) {
                continue;
            }
            int take = Math.min(remaining, stack.getAmount());
            remaining -= take;
            if (take >= stack.getAmount()) {
                player.getInventory().setItem(i, null);
            } else {
                stack.setAmount(stack.getAmount() - take);
            }
        }
        return true;
    }

    /** Removes every token a player is carrying. @return how many were removed */
    public int clearTokens(Player player) {
        int removed = 0;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isToken(contents[i])) {
                removed += contents[i].getAmount();
                player.getInventory().setItem(i, null);
            }
        }
        return removed;
    }

    /** Manual grant (an admin or gamemaster override) — does not count towards {@link #earnedTotal}. */
    public void giveManually(String actor, Player target, int amount) {
        giveTokens(target, amount);
        roundLog.log("SPONSOR", actor + " gave " + target.getName() + " " + amount + " token(s)");
    }

    /** Tokens a player has earned through the wave schedule this round (manual grants do not count). */
    public int earnedTotal(UUID uuid) {
        return perPlayer.getOrDefault(uuid, Progress.NONE).tokensEarned();
    }

    private void giveTokens(Player player, int amount) {
        items.create(tokenItem, amount).ifPresent(stack -> give.give(player, stack));
    }

    // ==================== the wave tick ====================

    /**
     * Grants whatever wave(s) have come due since the last tick, to every eligible tribute currently
     * online. Offline tributes are caught up the next time this runs after they rejoin — see
     * {@link TokenSchedule}'s class note on why waves, not raw token counts, are what survives a rejoin.
     */
    public void tick(Duration elapsed, Schedule schedule, java.util.function.Function<UUID, Player> online) {
        if (session.phase() != GamePhase.RUNNING) {
            return;
        }
        int due = TokenSchedule.dueWaves(elapsed, schedule.firstAfter(), schedule.interval());
        if (due <= 0) {
            return;
        }
        boolean changed = false;
        for (var participant : session.participants().all()) {
            if (schedule.onlyAlive() && !session.participants().isAlive(participant.uuid())) {
                continue;
            }
            Player player = online.apply(participant.uuid());
            if (player == null) {
                continue;
            }
            Progress before = perPlayer.getOrDefault(participant.uuid(), Progress.NONE);
            int pending = TokenSchedule.pendingTokens(due, before.wavesReceived(), schedule.amountPerWave(),
                    before.tokensEarned(), schedule.maxPerPlayer());
            int earned = before.tokensEarned() + Math.max(0, pending);
            perPlayer.put(participant.uuid(), new Progress(due, earned));
            changed = true;
            if (pending <= 0) {
                continue;
            }
            giveTokens(player, pending);
            roundLog.log("SPONSOR", participant.lastKnownName() + " received " + pending
                    + " token(s) (total " + earned + ")");
            announcements.send(participant.uuid(), player, "sponsor-token-earned",
                    new AnnouncementService.Style[]{AnnouncementService.Style.CHAT,
                            AnnouncementService.Style.ACTIONBAR},
                    "amount", String.valueOf(pending));
        }
        if (changed) {
            persist();
        }
    }

    // ==================== reset ====================

    /** Round over — every player's progress is forgotten, and the caller decides what to do with the item. */
    public void resetForNewRound() {
        perPlayer.clear();
        runtimeStore.saveTokenState(Map.of());
    }

    private void persist() {
        Map<UUID, RuntimeStore.TokenState> state = new LinkedHashMap<>();
        perPlayer.forEach((uuid, progress) ->
                state.put(uuid, new RuntimeStore.TokenState(progress.wavesReceived(), progress.tokensEarned())));
        runtimeStore.saveTokenState(state);
    }

    @Override
    public String describe() {
        return "sponsor tokens, earned over time and spent at a beacon";
    }
}
