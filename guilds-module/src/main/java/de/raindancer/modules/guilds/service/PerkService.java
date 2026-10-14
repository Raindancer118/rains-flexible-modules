package de.raindancer.modules.guilds.service;

import de.raindancer.core.data.stash.ArmourPiece;
import de.raindancer.modules.guilds.GuildProgressSettings;
import de.raindancer.modules.guilds.GuildsSettings;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.GuildEvent;
import de.raindancer.modules.guilds.model.GuildOutcome;
import de.raindancer.modules.guilds.model.GuildPermission;
import de.raindancer.modules.guilds.model.Perk;
import de.raindancer.modules.guilds.model.Standing;
import de.raindancer.modules.guilds.rules.CrownRule;
import de.raindancer.modules.guilds.rules.LevelRule;
import de.raindancer.modules.guilds.rules.PerkRule;
import de.raindancer.modules.guilds.store.GuildBook;
import de.raindancer.modules.guilds.store.ProgressBook;
import de.raindancer.modules.guilds.store.ProgressStorage;
import de.raindancer.modules.guilds.store.VaultStore;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * What a guild gets for its levels: perk points and what they buy, prestige past the top level, the weekly crown,
 * and the shared vault. Everything a perk changes elsewhere — member cap, ally slots, the home's wait, guild and
 * vanilla experience — is asked of here, so a perk has one meaning.
 */
public final class PerkService implements IGuildsService {

    /** Effects made here: short, quiet and renewed — a potion a player drinks always wins, nothing is left over. */
    private static final int EFFECT_TICKS = 20 * 20;
    private static final int RENEW_BELOW_TICKS = 20 * 11;

    private final GuildBook book;
    private final ProgressBook progress;
    private final GuildNotifier notify;
    private final VaultStore vaults;
    private final LongSupplier clock;
    private final CrownRule crowns = new CrownRule();
    /** Guilds being disbanded from this server: their vault was empty when checked and takes nothing more until it is over. */
    private final Set<String> sealed = new java.util.HashSet<>();
    private volatile GuildsSettings settings;
    private volatile GuildProgressSettings more;
    private volatile LevelRule levels;
    private volatile PerkRule rule;

    /** @param vaults where guild vaults live — required: "no store" must never read as "an empty vault" */
    public PerkService(GuildBook book, ProgressBook progress, GuildNotifier notify, VaultStore vaults,
                       LongSupplier clock, GuildsSettings settings, GuildProgressSettings more) {
        this.book = book;
        this.progress = progress;
        this.notify = notify;
        this.vaults = java.util.Objects.requireNonNull(vaults, "vaults");
        this.clock = clock;
        settings(settings);
        progressSettings(more);
    }

    @Override
    public void settings(GuildsSettings settings) {
        this.settings = settings;
        this.levels = GuildService.levelsFrom(settings);
    }

    public void progressSettings(GuildProgressSettings more) {
        this.more = more;
        this.rule = new PerkRule(more.pointsPerPrestige(), more.pointsPerCrown(), Duration.ofDays(more.respecDays()));
    }

    public PerkRule rule() {
        return rule;
    }

    private long now() {
        return clock.getAsLong();
    }

    private void log(Guild guild, GuildEvent.Kind kind, UUID actor, String detail) {
        book.log(GuildEvent.of(guild.id().value(), now(), kind, actor, null, 0, detail));
    }

    // ------------------------------------------------------------------ standing

    /** A point for every level a guild already has, for one that levelled before perks existed. */
    public int levelPointsSoFar(Guild guild) {
        return Math.max(0, levels.levelOf(guild.xp()) - 1);
    }

    public Standing standing(Guild guild) {
        return progress.standing(guild.id().value(), () -> levelPointsSoFar(guild));
    }

    /** The guild went up {@code gained} levels: a point each. Called by the experience service. */
    public void levelledUp(Guild before, int gained) {
        if (gained > 0) {
            progress.standing(before.id().value(), () -> levelPointsSoFar(before),
                    standing -> standing.withLevelPoints(standing.levelPoints() + gained));
        }
    }

    public int unspent(Guild guild) {
        return rule.unspent(standing(guild));
    }

    public int extraMembers(Guild guild) {
        return PerkRule.extraMembers(standing(guild));
    }

    public int extraAllies(Guild guild) {
        return PerkRule.extraAllies(standing(guild));
    }

    /** The guild's experience bonus from perks and prestige, in percent. */
    public int guildXpPercent(Guild guild) {
        return PerkRule.guildXpPercent(standing(guild), more.percentPerPrestige());
    }

    public int homeTravelPercent(Guild guild) {
        return PerkRule.homeTravelPercent(standing(guild));
    }

    // ------------------------------------------------------------------ buying and taking back

    public GuildOutcome raise(UUID actor, Perk perk) {
        Optional<Guild> found = book.guildOf(actor);
        if (found.isEmpty()) {
            return GuildOutcome.NO_GUILD;
        }
        Guild guild = found.get();
        if (!guild.may(actor, GuildPermission.PERKS)) {
            return GuildOutcome.NOT_ALLOWED;
        }
        int level = levels.levelOf(guild.xp());
        GuildOutcome[] answer = {GuildOutcome.SUCCESS};
        Standing after = progress.standing(guild.id().value(), () -> levelPointsSoFar(guild), standing -> {
            answer[0] = rule.mayRaise(standing, perk, level);
            return answer[0].isSuccess() ? standing.withPerk(perk, standing.rank(perk) + 1) : standing;
        });
        if (answer[0].isSuccess()) {
            log(guild, GuildEvent.Kind.PERK, actor, perk.title() + " " + after.rank(perk));
            notify.members(guild, "guilds.perk.bought", "perk", perk.title(), "rank", after.rank(perk),
                    "player", notify.name(actor));
        }
        return answer[0];
    }

    public GuildOutcome respec(UUID actor) {
        Optional<Guild> found = book.guildOf(actor);
        if (found.isEmpty()) {
            return GuildOutcome.NO_GUILD;
        }
        Guild guild = found.get();
        if (!guild.may(actor, GuildPermission.PERKS)) {
            return GuildOutcome.NOT_ALLOWED;
        }
        GuildOutcome[] answer = {GuildOutcome.SUCCESS};
        progress.standing(guild.id().value(), () -> levelPointsSoFar(guild), standing -> {
            answer[0] = rule.mayRespec(standing, now());
            return answer[0].isSuccess() ? standing.respecced(now()) : standing;
        });
        if (answer[0].isSuccess()) {
            log(guild, GuildEvent.Kind.PERK, actor, "points taken back");
            notify.members(guild, "guilds.perk.respecced", "player", notify.name(actor));
        }
        return answer[0];
    }

    // ------------------------------------------------------------------ prestige

    /** The leader takes a guild at the top level back to level 1, for a star, points and a lasting bonus. */
    public GuildOutcome prestige(UUID actor) {
        Optional<Guild> found = book.guildOf(actor);
        if (found.isEmpty()) {
            return GuildOutcome.NO_GUILD;
        }
        Guild guild = found.get();
        if (!guild.leader().equals(actor)) {
            return GuildOutcome.NOT_ALLOWED;
        }
        if (!levels.isTop(levels.levelOf(guild.xp()))) {
            return GuildOutcome.NOT_TOP_LEVEL;
        }
        // The standing first, made from the old level, so the points for the levels just left are not lost.
        standing(guild);
        boolean[] reset = {false};
        GuildOutcome changed = book.update(guild.id(), now -> {
            if (!levels.isTop(levels.levelOf(now.xp()))) {
                return now;
            }
            reset[0] = true;
            return now.withXp(0);
        });
        if (!changed.isSuccess() || !reset[0]) {
            return changed.isSuccess() ? GuildOutcome.NOT_TOP_LEVEL : changed;
        }
        Standing after = progress.standing(guild.id().value(), () -> 0, standing -> standing.withPrestige(standing.prestige() + 1));
        log(guild, GuildEvent.Kind.PRESTIGE, actor, PerkRule.stars(after.prestige()));
        notify.everybody("guilds.prestige.announced", "guild", GuildNotifier.title(guild),
                "stars", PerkRule.stars(after.prestige()), "bonus", more.percentPerPrestige() * after.prestige());
        return GuildOutcome.SUCCESS;
    }

    /** "★2" and "♛" for the name badge: prestige stars and this week's crown. Empty for a guild with neither. */
    public Optional<String> honours(Guild guild) {
        String stars = PerkRule.stars(standing(guild).prestige());
        boolean crowned = isCrowned(guild);
        String shown = (stars + (crowned ? "♛" : "")).strip();
        return shown.isEmpty() ? Optional.empty() : Optional.of(shown);
    }

    // ------------------------------------------------------------------ the crown

    public boolean isCrowned(Guild guild) {
        ProgressStorage.CrownState crown = progress.crown();
        return crown.guild().equals(guild.id().value())
                && crown.week() == de.raindancer.modules.guilds.rules.ContractRule.weekOf(now());
    }

    public List<CrownRule.Place> podium(long week) {
        return crowns.podium(progress.weekXp(week), 3);
    }

    /**
     * Settles last week's race the first time it is asked in a new week: the top three are announced, the winner
     * wears the crown this week and gets its point. Asked every minute; does nothing until the week turns.
     *
     * @return whether a week was settled now
     */
    public boolean settle() {
        long week = de.raindancer.modules.guilds.rules.ContractRule.weekOf(now());
        ProgressStorage.CrownState crown = progress.crown();
        if (crown.settledWeek() >= week - 1) {
            return false;
        }
        // Every server's share of last week's race, as the database has it, not as this server heard of it.
        progress.refreshAll();
        crown = progress.crown();
        if (crown.settledWeek() >= week - 1) {
            return false;
        }
        List<CrownRule.Place> podium = podium(week - 1);
        String winner = podium.isEmpty() ? "" : podium.getFirst().guild();
        // Of the servers settling the week, one moves the settled week on: it announces and gives the point.
        if (!progress.settleCrown(new ProgressStorage.CrownState(winner, week, week - 1))) {
            return false;
        }
        if (podium.isEmpty()) {
            return true;
        }
        Optional<Guild> champion = book.guild(winner);
        champion.ifPresent(guild -> progress.standing(guild.id().value(), () -> levelPointsSoFar(guild),
                standing -> standing.withCrowns(standing.crowns() + 1)));
        for (int place = 0; place < podium.size(); place++) {
            int shown = place + 1;
            CrownRule.Place each = podium.get(place);
            book.guild(each.guild()).ifPresent(guild -> notify.everybody("guilds.crown.place", "place", shown,
                    "guild", GuildNotifier.title(guild), "xp", each.xp()));
        }
        champion.ifPresent(guild -> {
            log(guild, GuildEvent.Kind.CROWN, null, "week " + (week - 1));
            notify.members(guild, "guilds.crown.won", "points", more.pointsPerCrown());
        });
        return true;
    }

    // ------------------------------------------------------------------ what members feel

    /** Keeps a member's perk effects on, on their own thread. Called every few seconds per online member. */
    public void refreshEffects(Player player) {
        Optional<Guild> found = book.guildOf(player.getUniqueId());
        Standing standing = found.map(this::standing).orElse(Standing.NONE);
        renew(player, PotionEffectType.HASTE, PerkRule.hasteAmplifier(standing));
        renew(player, PotionEffectType.LUCK, PerkRule.luckAmplifier(standing));
    }

    private static void renew(Player player, PotionEffectType type, int amplifier) {
        if (amplifier < 0) {
            return;
        }
        PotionEffect current = player.getPotionEffect(type);
        if (current != null && (current.getAmplifier() > amplifier
                || (current.getAmplifier() == amplifier && (current.isInfinite() || current.getDuration() > RENEW_BELOW_TICKS)))) {
            return;
        }
        player.addPotionEffect(new PotionEffect(type, EFFECT_TICKS, amplifier, true, false, true));
    }

    /** Vanilla experience a member picks up, with their guild's Scholar perk. */
    public int vanillaXp(UUID player, int amount) {
        if (amount <= 0) {
            return amount;
        }
        int percent = book.guildOf(player).map(guild -> PerkRule.vanillaXpPercent(standing(guild))).orElse(0);
        return percent <= 0 ? amount : (int) Math.min(Integer.MAX_VALUE, (long) amount * (100 + percent) / 100);
    }

    // ------------------------------------------------------------------ the vault

    /**
     * The vault's key: the guild's id <em>and</em> when it was founded. By id alone, a guild founded again
     * under a disbanded guild's name would open whatever that guild left behind.
     */
    static UUID vaultOwner(Guild guild) {
        return GuildBook.vaultOwner(guild);
    }

    private static String key(Guild guild) {
        return vaultOwner(guild).toString();
    }

    /** The guild as it is now, if {@code player} is still in it — never the copy a screen was drawn from. */
    private Optional<Guild> current(Guild guild, UUID player) {
        return book.guild(guild.id()).filter(now -> now.isMember(player));
    }

    /**
     * For a disband: says whether the vault is empty and, if it is, seals it in the same step, so nothing can be
     * put in from this server between this check and the guild being gone. {@link #unseal} if the disband does not
     * happen. A deposit from another server is caught by the disband itself, which checks the vault in the same
     * write that deletes the guild.
     */
    public synchronized GuildOutcome sealForDisband(Guild guild) {
        Optional<VaultStore.Contents> held = vaults.read(key(guild));
        if (held.isEmpty()) {
            return GuildOutcome.VAULT_UNREADABLE;
        }
        if (!held.get().isEmpty()) {
            return GuildOutcome.VAULT_NOT_EMPTY;
        }
        sealed.add(guild.id().value());
        return GuildOutcome.SUCCESS;
    }

    public synchronized void unseal(Guild guild) {
        sealed.remove(guild.id().value());
    }

    /** The guild is gone: nothing here to let go of but the seal. Its rows are keyed to a founding no new guild can have. */
    public synchronized void forgetVault(String guild) {
        sealed.remove(guild);
    }

    /** How many stacks the vault takes now; what it already holds above that is kept, only deposits wait. */
    public int vaultSlots(Guild guild) {
        return PerkRule.vaultSlots(standing(guild));
    }

    /** Whether the vault can be opened: bought — or no longer bought but still holding things to take out. */
    public boolean hasVault(Guild guild) {
        return vaultSlots(guild) > 0 || !vaultEmpty(guild);
    }

    /** The stacks in the vault as the database has them; none when it cannot be read — see {@link #vaultEmpty}. */
    public List<ItemStack> vaultItems(Guild guild) {
        return vaults.read(key(guild)).map(VaultStore.Contents::items).orElse(List.of());
    }

    public Optional<ItemStack> vaultArmour(Guild guild, ArmourPiece piece) {
        return vaults.read(key(guild)).map(held -> held.armour().get(piece));
    }

    /** Whether the vault is empty: never "yes" for one that could not be read, or that holds a line this server cannot. */
    public boolean vaultEmpty(Guild guild) {
        return vaults.read(key(guild)).map(VaultStore.Contents::isEmpty).orElse(false);
    }

    /** @return how many of {@code item} went in; the caller takes exactly that many away */
    public int deposit(Guild guild, ItemStack item) {
        if (item == null || item.isEmpty()) {
            return 0;
        }
        synchronized (this) {
            if (sealed.contains(guild.id().value())) {
                return 0;
            }
        }
        Optional<Guild> now = book.guild(guild.id());
        if (now.isEmpty() || vaultSlots(now.get()) <= 0) {
            return 0;
        }
        return vaults.deposit(key(now.get()), now.get().id().value(), item, vaultSlots(now.get()));
    }

    /** Takes one entry out, if it is still what the page showed. Null when it is not, or they may not. */
    public ItemStack take(Guild guild, UUID player, int index, ItemStack shown) {
        Optional<Guild> now = current(guild, player).filter(each -> each.may(player, GuildPermission.VAULT));
        return now.isEmpty() ? null : vaults.take(key(now.get()), index, shown);
    }

    public ItemStack takeArmour(Guild guild, UUID player, ArmourPiece piece) {
        Optional<Guild> now = current(guild, player).filter(each -> each.may(player, GuildPermission.VAULT));
        return now.isEmpty() ? null : vaults.takeArmour(key(now.get()), piece);
    }

    /** Whatever of {@code back} would not fit in the player's inventory goes back in, never on the floor. */
    public void putBack(Guild guild, ItemStack back) {
        vaults.putBack(key(guild), guild.id().value(), back);
    }
}
