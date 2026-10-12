package de.raindancer.modules.guilds.rules;

import de.raindancer.modules.guilds.model.Contract;
import de.raindancer.modules.guilds.model.XpSource;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Which contracts a guild gets in a week, how big they are and what they pay.
 *
 * <p>Drawn from the guild's id and the week, so the same guild gets the same contracts however often they are
 * drawn — a restart or a second server reading the same database never deals a different hand. Never two of the
 * same kind in one week. Size grows with members as members^0.75: a guild of sixteen does eight times what one
 * member would, not sixteen, because not everybody is on at once.
 */
public final class ContractRule implements IGuildsRule {

    private static final long DAY = 86_400_000L;

    /** One kind of contract: per-member size before scaling. */
    public record Template(XpSource source, String what, String title, long perMember) {
    }

    public static final List<Template> POOL = List.of(
            new Template(XpSource.MONSTER, "", "Kill monsters", 120),
            new Template(XpSource.MONSTER, "zombie", "Kill zombies", 40),
            new Template(XpSource.MONSTER, "skeleton", "Kill skeletons", 40),
            new Template(XpSource.MONSTER, "creeper", "Kill creepers", 25),
            new Template(XpSource.MONSTER, "spider", "Kill spiders", 30),
            new Template(XpSource.MONSTER, "enderman", "Kill endermen", 15),
            new Template(XpSource.MONSTER, "blaze", "Kill blazes", 15),
            new Template(XpSource.MINE, "", "Mine any ore", 150),
            new Template(XpSource.MINE, "coal", "Mine coal ore", 80),
            new Template(XpSource.MINE, "iron", "Mine iron ore", 40),
            new Template(XpSource.MINE, "copper", "Mine copper ore", 60),
            new Template(XpSource.MINE, "gold", "Mine gold ore", 20),
            new Template(XpSource.MINE, "redstone", "Mine redstone ore", 40),
            new Template(XpSource.MINE, "lapis", "Mine lapis ore", 25),
            new Template(XpSource.MINE, "diamond", "Mine diamond ore", 6),
            new Template(XpSource.MINE, "quartz", "Mine nether quartz", 60),
            new Template(XpSource.FISH, "", "Catch fish", 30),
            new Template(XpSource.BREED, "", "Breed animals", 25),
            new Template(XpSource.TRADE, "", "Trade with villagers", 20),
            new Template(XpSource.HARVEST, "", "Harvest crops", 150),
            new Template(XpSource.HARVEST, "wheat", "Harvest wheat", 100),
            new Template(XpSource.HARVEST, "carrots", "Harvest carrots", 100),
            new Template(XpSource.HARVEST, "potatoes", "Harvest potatoes", 100),
            new Template(XpSource.HARVEST, "nether_wart", "Harvest nether wart", 60),
            new Template(XpSource.ENCHANT, "", "Spend levels enchanting", 60),
            new Template(XpSource.ADVANCEMENT, "", "Earn advancements", 4),
            new Template(XpSource.PLAY, "", "Play together (minutes)", 600));

    @Override
    public String describe() {
        return "a guild's week brings contracts of different kinds, sized by members^0.75, each paying a share of "
                + "its next level";
    }

    /** Weeks since the epoch, Mondays first. 1970-01-01 was a Thursday, hence the three. */
    public static long weekOf(long millis) {
        return Math.floorDiv(Math.floorDiv(millis, DAY) + 3, 7);
    }

    /** When that week ends, in epoch millis. */
    public static long weekEnds(long week) {
        return ((week + 1) * 7 - 3) * DAY;
    }

    /** What a contract pays: a percent of the step to the next level, at least 50. */
    public static long reward(long stepToNextLevel, int percent) {
        long step = Math.max(0, stepToNextLevel);
        long share = step > Long.MAX_VALUE / 1000 ? step / 100 * percent : step * percent / 100;
        return Math.max(50, share);
    }

    /** How much a template asks of a guild this size, rounded to a number people say. */
    public static long size(Template template, int members, int sizePercent) {
        double scaled = template.perMember() * Math.pow(Math.max(1, members), 0.75) * Math.max(1, sizePercent) / 100.0;
        long raw = Math.max(1, Math.round(scaled));
        long step = raw >= 1000 ? 50 : raw >= 100 ? 10 : raw >= 20 ? 5 : 1;
        return Math.max(1, Math.round((double) raw / step) * step);
    }

    public List<Contract> draw(String guild, long week, int members, int count, int sizePercent, long reward) {
        Random random = new Random(guild.hashCode() * 1_000_003L + week);
        List<Template> left = new ArrayList<>(POOL);
        Set<XpSource> taken = EnumSet.noneOf(XpSource.class);
        List<Contract> drawn = new ArrayList<>();
        int slot = 0;
        while (drawn.size() < count && !left.isEmpty()) {
            Template pick = left.remove(random.nextInt(left.size()));
            if (!taken.add(pick.source())) {
                continue;
            }
            drawn.add(new Contract(guild, week, slot++, pick.source(), pick.what(), pick.title(),
                    size(pick, members, sizePercent), 0, reward, false));
        }
        return drawn;
    }
}
