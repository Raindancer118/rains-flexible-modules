package de.raindancer.modules.jobs.service;

import de.raindancer.core.content.items.NonIngredients;
import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.social.economy.SaleStop;
import de.raindancer.core.ui.choose.Catalogue;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.jobs.JobsSettings;
import de.raindancer.modules.jobs.model.Goal;
import de.raindancer.modules.jobs.model.GoalKind;
import de.raindancer.modules.jobs.model.GoalTemplate;
import de.raindancer.modules.jobs.rules.LearningRule;
import de.raindancer.modules.jobs.rules.RewardRule;
import de.raindancer.modules.jobs.store.GoalBook;
import de.raindancer.modules.jobs.store.TemplateCatalogue;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/** Runs the board: puts goals up, counts hand-ins and catches, pays out, and learns the next size. */
public final class GoalService implements IJobsService, SaleStop {

    /** How long a worked-out reward is reused — it reads every player's balance. */
    private static final long POOL_FRESH_MILLIS = 5 * 60_000L;

    private final Server server;
    private final TemplateCatalogue templates;
    private final GoalBook book;
    private final Messages messages;
    private final LogChannel log;
    private final LongSupplier clock;
    private final Random random;
    private final RewardRule rewards = new RewardRule();
    private final LearningRule learning = new LearningRule();
    private volatile JobsSettings settings;
    private volatile Optional<Money> pool = Optional.empty();
    /** The kinds that ended last, newest first — not put straight back up, so the board changes. */
    private final java.util.Deque<String> recent = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private static final int REMEMBERED = 3;
    private volatile long poolAt;

    public GoalService(Server server, TemplateCatalogue templates, GoalBook book, Messages messages, LogChannel log,
                       LongSupplier clock, Random random, JobsSettings settings) {
        this.server = server;
        this.templates = templates;
        this.book = book;
        this.messages = messages;
        this.log = log;
        this.clock = clock;
        this.random = random;
        this.settings = settings == null ? JobsSettings.DEFAULTS : settings;
    }

    @Override
    public void settings(JobsSettings updated) {
        this.settings = updated == null ? JobsSettings.DEFAULTS : updated;
        poolAt = 0;
    }

    public List<Goal> goals() {
        return book.active();
    }

    public Optional<Goal> goal(long number) {
        return book.goal(number);
    }

    public List<GoalTemplate> templates() {
        return templates.all();
    }

    public int reload() {
        return templates.reload();
    }

    public List<String> problems() {
        return templates.problems();
    }

    public boolean saving() {
        return book.readable();
    }

    // ---------------------------------------------------------------------------- the board

    /** Ends what is due and fills the board. Once a minute, and at start. */
    public void tick() {
        long now = clock.getAsLong();
        for (Goal goal : book.active()) {
            if (goal.reached()) {
                finish(goal, true);
            } else if (now >= goal.endsAt()) {
                finish(goal, false);
            }
        }
        fill();
        book.flush();
    }

    /** Puts up goals until the board holds as many as it should. */
    public void fill() {
        int wanted = Math.max(1, settings.activeGoals());
        while (book.active().size() < wanted) {
            Set<String> running = book.active().stream().map(Goal::template).collect(Collectors.toSet());
            List<GoalTemplate> free = new ArrayList<>(templates.all().stream()
                    .filter(each -> !running.contains(each.id())).toList());
            if (free.isEmpty()) {
                return;
            }
            List<GoalTemplate> fresh = free.stream().filter(each -> !recent.contains(each.id())).toList();
            if (!fresh.isEmpty()) {
                free = new ArrayList<>(fresh);
            }
            Collections.shuffle(free, random);
            // A fishing goal and a delivery goal side by side, where both kinds are free: two of a kind at once
            // leaves the people who do the other with nothing.
            boolean fishing = book.active().stream().anyMatch(goal -> goal.kind() == GoalKind.FISH);
            GoalTemplate picked = free.stream().filter(each -> each.kind() == GoalKind.FISH != fishing).findFirst()
                    .orElse(free.getFirst());
            if (start(picked).isEmpty()) {
                return;
            }
        }
    }

    /** Puts one goal of this kind up now. */
    public Optional<Goal> start(GoalTemplate template) {
        long now = clock.getAsLong();
        int amount = Math.clamp(book.learned(template.id()).orElse(template.start()), template.least(), template.most());
        Goal goal = new Goal(book.nextNumber(), template.id(), template.title(), template.icon(), template.kind(),
                template.items(), amount, now, now + Duration.ofDays(template.days()).toMillis(), Map.of());
        if (!book.start(goal)) {
            log.error("A goal could not be saved to goals.yml, so it was not put up.");
            return Optional.empty();
        }
        if (settings.announce()) {
            server.getOnlinePlayers().forEach(player -> messages.send(player, "jobs.started",
                    "goal", goal.title(), "amount", String.valueOf(goal.amount()), "days", String.valueOf(template.days())));
        }
        return Optional.of(goal);
    }

    /** Ends a goal now: pays for what was reached, learns from it, and puts another up. */
    public boolean end(long number) {
        Optional<Goal> goal = book.goal(number);
        if (goal.isEmpty()) {
            return false;
        }
        finish(goal.get(), goal.get().reached());
        fill();
        return true;
    }

    private void finish(Goal goal, boolean reached) {
        if (book.end(goal.number()).isEmpty()) {
            return;
        }
        recent.addFirst(goal.template());
        while (recent.size() > REMEMBERED) {
            recent.removeLast();
        }
        long now = clock.getAsLong();
        templates.find(goal.template()).ifPresent(template -> book.learn(template.id(),
                learning.next(goal.amount(), goal.progress(), goal.startedAt(), now, goal.endsAt() - goal.startedAt(),
                        template.least(), template.most())));
        Optional<Economy> economy = Economies.current();
        Money reward = poolNow().orElse(Money.ZERO);
        Map<UUID, Money> paid = rewards.payouts(goal.given(), goal.amount(), reward);
        if (economy.isEmpty() && !paid.isEmpty()) {
            log.warn("Goal {} ended, but there is no economy to pay {} contributor(s) from.", goal.title(), paid.size());
        }
        int progress = goal.progress();
        paid.forEach((player, money) -> {
            economy.ifPresent(bank -> {
                var result = bank.deposit(player, money, "Server goal: " + goal.title());
                if (!result.succeeded()) {
                    log.warn("{} could not be paid for goal {}: {}", player, goal.title(), result.outcome());
                }
            });
            Player online = server.getPlayer(player);
            if (online != null) {
                int percent = (int) Math.round(goal.givenBy(player) * 100.0 / Math.max(1, progress));
                messages.send(online, reached ? "jobs.paid" : "jobs.paid-part", "goal", goal.title(),
                        "percent", String.valueOf(percent),
                        "amount", economy.map(bank -> (Object) bank.currency().render(money)).orElse(String.valueOf(money.minor())));
            }
        });
        if (settings.announce()) {
            server.getOnlinePlayers().forEach(player -> messages.send(player, reached ? "jobs.reached" : "jobs.missed",
                    "goal", goal.title(), "progress", String.valueOf(Math.min(progress, goal.amount())),
                    "amount", String.valueOf(goal.amount()), "players", String.valueOf(paid.size())));
        }
    }

    // ---------------------------------------------------------------------------- the reward

    /** What a reached goal pays, all told — the median balance of the players seen lately, as the owner set. */
    public Optional<Money> poolNow() {
        long now = clock.getAsLong();
        if (now - poolAt < POOL_FRESH_MILLIS && pool.isPresent()) {
            return pool;
        }
        Optional<Economy> economy = Economies.current();
        if (economy.isEmpty()) {
            return Optional.empty();
        }
        JobsSettings live = settings;
        long since = now - Duration.ofDays(live.medianOfDays()).toMillis();
        List<Money> balances = new ArrayList<>();
        for (OfflinePlayer player : server.getOfflinePlayers()) {
            if ((player.isOnline() || player.getLastSeen() >= since) && economy.get().hasAccount(player.getUniqueId())) {
                balances.add(economy.get().balance(player.getUniqueId()));
            }
        }
        Money least = economy.get().currency().parse(live.leastReward()).orElse(Money.ZERO);
        Money most = live.mostReward() == null || live.mostReward().isBlank() ? Money.ZERO
                : economy.get().currency().parse(live.mostReward()).orElse(Money.ZERO);
        pool = Optional.of(rewards.pool(rewards.median(balances), live.rewardPercent(), least, most));
        poolAt = now;
        return pool;
    }

    /** What this player would be paid if the goal ended now. */
    public Optional<Money> estimate(Goal goal, UUID player) {
        return poolNow().map(reward -> rewards.payouts(goal.given(), goal.amount(), reward).getOrDefault(player, Money.ZERO));
    }

    // ---------------------------------------------------------------------------- working on goals

    /** How many plain items this goal would take from this inventory. */
    public int carrying(Player player, Goal goal) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (counts(goal, stack)) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    private static boolean counts(Goal goal, ItemStack stack) {
        return stack != null && !stack.getType().isAir() && goal.items().covers(stack.getType().name())
                && stack.isSimilar(new ItemStack(stack.getType())) && !NonIngredients.isMarked(stack);
    }

    /** Hands in everything this player carries that the goal still needs. */
    public void deliver(Player player, long number) {
        Optional<Goal> found = book.goal(number);
        if (found.isEmpty() || found.get().kind() != GoalKind.DELIVER) {
            messages.send(player, "jobs.gone");
            return;
        }
        Goal goal = found.get();
        int wanted = Math.min(goal.left(), carrying(player, goal));
        if (wanted <= 0) {
            messages.send(player, "jobs.none-carried", "goal", goal.title(), "what", goal.items().says());
            return;
        }
        List<ItemStack> taken = take(player.getInventory(), goal, wanted);
        int count = taken.stream().mapToInt(ItemStack::getAmount).sum();
        Optional<Goal> now = book.add(number, player.getUniqueId(), count);
        if (now.isEmpty()) {
            player.getInventory().addItem(taken.toArray(ItemStack[]::new)).values()
                    .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
            messages.send(player, "jobs.not-saved");
            return;
        }
        messages.send(player, "jobs.delivered", "count", String.valueOf(count), "goal", goal.title(),
                "progress", String.valueOf(now.get().progress()), "amount", String.valueOf(goal.amount()));
        if (now.get().reached()) {
            finish(now.get(), true);
            fill();
        }
    }

    private static List<ItemStack> take(PlayerInventory inventory, Goal goal, int wanted) {
        List<ItemStack> taken = new ArrayList<>();
        ItemStack[] contents = inventory.getStorageContents();
        int left = wanted;
        // Last slots first, so the hotbar is emptied last — the same order the shop sells in.
        for (int i = contents.length - 1; i >= 0 && left > 0; i--) {
            ItemStack stack = contents[i];
            if (!counts(goal, stack)) {
                continue;
            }
            int moved = Math.min(left, stack.getAmount());
            ItemStack part = stack.clone();
            part.setAmount(moved);
            taken.add(part);
            if (moved == stack.getAmount()) {
                inventory.setItem(i, null);
            } else {
                stack.setAmount(stack.getAmount() - moved);
                inventory.setItem(i, stack);
            }
            left -= moved;
        }
        return taken;
    }

    /** A fish caught with a rod: counted for every fishing goal it belongs to. */
    public void caught(Player player, ItemStack fish) {
        if (fish == null) {
            return;
        }
        for (Goal goal : book.active()) {
            if (goal.kind() != GoalKind.FISH || !goal.items().covers(fish.getType().name())) {
                continue;
            }
            Optional<Goal> now = book.addLater(goal.number(), player.getUniqueId(), fish.getAmount());
            if (now.isEmpty()) {
                continue;
            }
            player.sendActionBar(messages.get("jobs.caught", "goal", goal.title(),
                    "progress", String.valueOf(now.get().progress()), "amount", String.valueOf(goal.amount())));
            if (now.get().reached()) {
                finish(now.get(), true);
                fill();
            }
        }
    }

    // ---------------------------------------------------------------------------- the shop

    @Override
    public Optional<String> reason(String material) {
        if (!settings.stopSales()) {
            return Optional.empty();
        }
        return book.active().stream().filter(goal -> goal.kind() == GoalKind.DELIVER && goal.items().covers(material))
                .findFirst().map(goal -> "the server is collecting it for \"" + goal.title() + "\" (/jobs)");
    }

    /** "Cod" or "any log, Oak Stem…" — what a goal counts, for messages. */
    public static String what(Goal goal) {
        return goal.items().items().size() == 1 && goal.items().categories().isEmpty()
                && !goal.items().items().getFirst().contains("*")
                ? Catalogue.readable(goal.items().items().getFirst()) : goal.items().says();
    }

    public void flush() {
        book.flush();
    }

    /** For the board: a name for whoever gave something. */
    public String nameOf(UUID player) {
        String name = server.getOfflinePlayer(player).getName();
        return name == null ? player.toString().substring(0, 8) : name;
    }

    public Material iconOf(Goal goal) {
        Material icon = Material.matchMaterial(goal.icon());
        return icon == null || !icon.isItem() ? Material.CHEST : icon;
    }
}
