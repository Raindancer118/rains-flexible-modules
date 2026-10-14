package de.raindancer.modules.guilds.rules;

import de.raindancer.modules.guilds.store.ShardBook;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** How many shards of which enchantment a member pays in: what they carry, each only up to what the guild still needs. */
public final class ShardPayRule implements IGuildsRule {

    /**
     * @param carried  shards carried, by enchantment
     * @param progress how far the guild is with an enchantment
     * @param needed   shards one enchantment takes to unlock
     * @return what to pay in, by enchantment; nothing for one unlocked, fully paid or not carried
     */
    public Map<String, Integer> giving(Map<String, Integer> carried, Function<String, ShardBook.Progress> progress,
                                       int needed) {
        Map<String, Integer> giving = new LinkedHashMap<>();
        carried.forEach((enchantment, count) -> {
            ShardBook.Progress now = progress.apply(enchantment);
            if (now == null) {
                now = ShardBook.Progress.NONE;
            }
            int amount = now.unlocked() ? 0 : Math.min(count, Math.max(0, needed - now.paid()));
            if (amount > 0) {
                giving.put(enchantment, amount);
            }
        });
        return giving;
    }

    @Override
    public String describe() {
        return "how many shards of which enchantment a member pays in";
    }
}
