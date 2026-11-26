package fr.chatplugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.scheduler.BukkitTask;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Chat réaction : craft, mot à compléter, capitale.
 */
public final class ReactionManager implements Listener {

    public enum Type {
        CRAFT("craft"), MOT("mot"), CAPITALE("capitale");

        public final String key;

        Type(String key) {
            this.key = key;
        }

        public static Type fromKey(String s) {
            for (Type t : values()) if (t.key.equalsIgnoreCase(s)) return t;
            return null;
        }
    }

    private static final class Game {
        Type type;
        Material material;          // CRAFT uniquement
        Set<String> accepted = new HashSet<>(); // réponses normalisées (MOT / CAPITALE)
        String display;             // réponse affichée
        String question;
        int duree;
        long startedAt = System.currentTimeMillis();
        final AtomicBoolean finished = new AtomicBoolean(false);
        BukkitTask timeout;
    }

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final ChatPlugin plugin;
    private volatile Game current;
    private BukkitTask scheduler;

    public ReactionManager(ChatPlugin plugin) {
        this.plugin = plugin;
    }

    /* ---------------- Cycle de vie ---------------- */

    public void reload() {
        stopAll();
        FileConfiguration c = plugin.getConfig();
        if (!c.getBoolean("reactions.actif", true)) return;

        long ticks = Math.max(10, c.getLong("reactions.intervalle", 600)) * 20L;
        scheduler = Bukkit.getScheduler().runTaskTimer(plugin, this::startRandom, ticks, ticks);
    }

    public void stopAll() {
        if (scheduler != null) {
            scheduler.cancel();
            scheduler = null;
        }
        stopCurrent();
    }

    public synchronized boolean stopCurrent() {
        Game g = current;
        if (g == null) return false;
        g.finished.set(true);
        if (g.timeout != null) g.timeout.cancel();
        current = null;
        return true;
    }

    public boolean isRunning() {
        return current != null;
    }

    /* ---------------- Lancement ---------------- */

    private void startRandom() {
        FileConfiguration c = plugin.getConfig();
        if (current != null) return;
        if (Bukkit.getOnlinePlayers().size() < c.getInt("reactions.joueurs-minimum", 1)) return;

        List<Type> enabled = new ArrayList<>();
        for (Type t : Type.values()) {
            if (c.getBoolean("reactions.types." + t.key + ".actif", true)) enabled.add(t);
        }
        if (enabled.isEmpty()) return;
        start(enabled.get(ThreadLocalRandom.current().nextInt(enabled.size())));
    }

    public synchronized boolean start(Type type) {
        if (current != null) return false;

        Game g = switch (type) {
            case CRAFT -> buildCraft();
            case MOT -> buildMot();
            case CAPITALE -> buildCapitale();
        };
        if (g == null) return false;

        g.type = type;
        g.duree = Math.max(1, plugin.getConfig().getInt("reactions.types." + type.key + ".duree",
                type == Type.CRAFT ? 25 : 15));
        current = g;

        Bukkit.broadcast(msg("debut-" + type.key,
                Placeholder.unparsed("question", g.question),
                Placeholder.unparsed("duree", String.valueOf(g.duree))));

        final Game game = g;
        g.timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> expire(game), g.duree * 20L);
        return true;
    }

    private Game buildCraft() {
        List<String> entries = plugin.getConfig().getStringList("reactions.types.craft.items");
        if (entries.isEmpty()) return null;
        String entry = entries.get(ThreadLocalRandom.current().nextInt(entries.size()));
        String[] parts = entry.split(";", 2);

        Material mat = Material.matchMaterial(parts[0].trim());
        if (mat == null) {
            plugin.getLogger().warning("Matériau invalide dans reactions.types.craft.items : " + parts[0]);
            return null;
        }
        Game g = new Game();
        g.material = mat;
        g.display = parts.length > 1 ? parts[1].trim() : mat.name();
        g.question = g.display;
        return g;
    }

    private Game buildMot() {
        List<String> words = plugin.getConfig().getStringList("reactions.types.mot.mots");
        if (words.isEmpty()) return null;
        String word = words.get(ThreadLocalRandom.current().nextInt(words.size())).trim();

        Game g = new Game();
        g.display = word;
        g.accepted.add(normalize(word));
        g.question = mask(word);
        return g;
    }

    private Game buildCapitale() {
        List<String> entries = plugin.getConfig().getStringList("reactions.types.capitale.questions");
        if (entries.isEmpty()) return null;
        String entry = entries.get(ThreadLocalRandom.current().nextInt(entries.size()));
        String[] parts = entry.split(";", 2);
        if (parts.length < 2) {
            plugin.getLogger().warning("Format invalide (attendu Pays;Capitale) : " + entry);
            return null;
        }
        Game g = new Game();
        g.question = parts[0].trim();
        String[] answers = parts[1].split("/");
        g.display = answers[0].trim();
        for (String a : answers) g.accepted.add(normalize(a));
        return g;
    }

    /* ---------------- Fin de partie ---------------- */

    private void expire(Game g) {
        if (!g.finished.compareAndSet(false, true)) return;
        synchronized (this) {
            if (current == g) current = null;
        }
        Bukkit.broadcast(msg("perdu", Placeholder.unparsed("reponse", g.display)));
    }

    private void win(Game g, Player player) {
        synchronized (this) {
            if (current == g) current = null;
        }
        if (g.timeout != null) g.timeout.cancel();

        double secs = (System.currentTimeMillis() - g.startedAt) / 1000.0;
        Reward reward = pickReward();

        Bukkit.broadcast(msg("gagne",
                Placeholder.unparsed("joueur", player.getName()),
                Placeholder.unparsed("reponse", g.display),
                Placeholder.unparsed("temps", String.format(Locale.ROOT, "%.1f", secs)),
                Placeholder.parsed("recompense", reward != null ? reward.nom() : "rien")));

        if (reward != null) {
            for (String cmd : reward.commandes()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%joueur%", player.getName()));
            }
        }
    }

    /* ---------------- Récompenses aléatoires ---------------- */

    private record Reward(String nom, double chance, List<String> commandes) {}

    /** Tirage pondéré : la probabilité d'une récompense = sa chance / somme des chances. */
    private Reward pickReward() {
        List<Reward> rewards = new ArrayList<>();
        double total = 0;

        for (Map<?, ?> map : plugin.getConfig().getMapList("reactions.recompenses")) {
            double chance = map.get("chance") instanceof Number n ? n.doubleValue() : 0;
            if (chance <= 0) continue;

            String nom = map.get("nom") != null ? String.valueOf(map.get("nom")) : "une récompense";
            List<String> cmds = new ArrayList<>();
            if (map.get("commandes") instanceof List<?> list) {
                for (Object o : list) cmds.add(String.valueOf(o));
            }
            rewards.add(new Reward(nom, chance, cmds));
            total += chance;
        }
        if (rewards.isEmpty()) return null;

        double roll = ThreadLocalRandom.current().nextDouble() * total;
        double cumulative = 0;
        for (Reward r : rewards) {
            cumulative += r.chance();
            if (roll < cumulative) return r;
        }
        return rewards.get(rewards.size() - 1);
    }

    /* ---------------- Réponses ---------------- */

    /**
     * Appelé depuis le chat (thread asynchrone).
     * @return true si le message était la bonne réponse (le message est alors consommé).
     */
    public boolean handleAnswer(Player player, String message) {
        Game g = current;
        if (g == null || g.type == Type.CRAFT) return false;
        if (!g.accepted.contains(normalize(message))) return false;
        if (!g.finished.compareAndSet(false, true)) return false;

        Bukkit.getScheduler().runTask(plugin, () -> win(g, player));
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        Game g = current;
        if (g == null || g.type != Type.CRAFT) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getRecipe().getResult().getType() != g.material) return;
        if (!g.finished.compareAndSet(false, true)) return;

        win(g, player);
    }

    /* ---------------- Utilitaires ---------------- */

    private Component msg(String key, TagResolver... resolvers) {
        FileConfiguration c = plugin.getConfig();
        String prefix = c.getString("reactions.prefixe", "");
        return MM.deserialize(prefix + c.getString("reactions.messages." + key, ""), resolvers);
    }

    private static String normalize(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replace('-', ' ').replaceAll("\\s+", " ").trim();
    }

    /** Cache ~40 % des lettres (sauf la première) : cheval -> c _ e v _ l */
    private static String mask(String word) {
        List<Integer> candidates = new ArrayList<>();
        for (int i = 1; i < word.length(); i++) {
            if (Character.isLetter(word.charAt(i))) candidates.add(i);
        }
        Collections.shuffle(candidates);
        int hide = candidates.isEmpty() ? 0 : Math.max(1, (int) Math.round(candidates.size() * 0.4));
        Set<Integer> hidden = new HashSet<>(candidates.subList(0, hide));

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < word.length(); i++) {
            sb.append(hidden.contains(i) ? '_' : word.charAt(i)).append(' ');
        }
        return sb.toString().trim();
    }
}
