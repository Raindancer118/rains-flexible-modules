package de.raindancer.modules.voicebridge.service;

import club.minnced.discord.jdave.interop.JDaveSessionFactory;
import de.raindancer.core.platform.log.LogChannel;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The proximity bots: one Discord bot carries one listener, because a bot sends one stream per voice
 * channel and everybody in a channel hears the same thing. A personal mix therefore needs a personal
 * bot. Logged in once, lent out and handed back.
 */
public final class BotPool {

    /** One logged-in proximity bot. */
    public static final class LineBot {
        private final JDA jda;
        private boolean busy;

        LineBot(JDA jda) {
            this.jda = jda;
        }

        public JDA jda() {
            return jda;
        }

        public long id() {
            return jda.getSelfUser().getIdLong();
        }
    }

    private final LogChannel log;
    private final List<LineBot> bots = new ArrayList<>();

    public BotPool(LogChannel log) {
        this.log = log;
    }

    /** Logs every token in; blocks, so call it off the server thread. A bad token is skipped, not fatal. */
    public void start(List<String> tokens, String guildId) {
        shutdown();
        int number = 0;
        for (String token : tokens) {
            number++;
            try {
                JDA jda = JDABuilder.createLight(token, EnumSet.of(GatewayIntent.GUILD_VOICE_STATES))
                        .enableCache(CacheFlag.VOICE_STATE)
                        .setMemberCachePolicy(MemberCachePolicy.VOICE)
                        .setAudioModuleConfig(new AudioModuleConfig().withDaveSessionFactory(new JDaveSessionFactory()))
                        .build();
                jda.awaitReady();
                if (jda.getGuildById(guildId) == null) {
                    log.warn("Proximity bot {} ({}) is not on the Discord server — invite it.", number,
                            jda.getSelfUser().getName());
                    jda.shutdownNow();
                    continue;
                }
                synchronized (bots) {
                    bots.add(new LineBot(jda));
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException | LinkageError broken) {
                log.warn("Proximity bot {} could not log in: {}", number, broken.getMessage());
            }
        }
        if (number > 0) {
            log.info("{} of {} proximity bot(s) ready.", size(), number);
        }
    }

    public Optional<LineBot> claim() {
        synchronized (bots) {
            for (LineBot bot : bots) {
                if (!bot.busy) {
                    bot.busy = true;
                    return Optional.of(bot);
                }
            }
        }
        return Optional.empty();
    }

    public void release(LineBot bot) {
        synchronized (bots) {
            bot.busy = false;
        }
    }

    public int size() {
        synchronized (bots) {
            return bots.size();
        }
    }

    public int free() {
        synchronized (bots) {
            return (int) bots.stream().filter(bot -> !bot.busy).count();
        }
    }

    public boolean isLineBot(long userId) {
        synchronized (bots) {
            return bots.stream().anyMatch(bot -> bot.id() == userId);
        }
    }

    public void shutdown() {
        List<LineBot> all;
        synchronized (bots) {
            all = List.copyOf(bots);
            bots.clear();
        }
        for (LineBot bot : all) {
            bot.jda.shutdown();
        }
        for (LineBot bot : all) {
            try {
                if (!bot.jda.awaitShutdown(5, TimeUnit.SECONDS)) {
                    bot.jda.shutdownNow();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                bot.jda.shutdownNow();
            }
        }
    }
}
