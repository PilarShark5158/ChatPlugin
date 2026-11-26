package fr.chatplugin;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Transforme "./caisse" écrit dans le chat en [/caisse] cliquable.
 */
public final class ChatListener implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final ChatPlugin plugin;

    /** Dernier message envoyé par chaque joueur (en ms). */
    private final Map<UUID, Long> lastMessage = new ConcurrentHashMap<>();

    public ChatListener(ChatPlugin plugin) {
        this.plugin = plugin;
    }

    /* ---------------- Cooldown du chat ---------------- */

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCooldown(AsyncChatEvent event) {
        FileConfiguration config = plugin.getConfig();

        var player = event.getPlayer();

        // Une bonne réponse au chat réaction passe avant le cooldown
        String plain = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (plugin.reactions().handleAnswer(player, plain)) {
            event.setCancelled(true);
            return;
        }

        if (!config.getBoolean("cooldown.actif", true)) {
            return;
        }
        if (player.hasPermission("chatplugin.bypass")) return;

        long cooldownMs = (long) (config.getDouble("cooldown.secondes", 5) * 1000);
        long now = System.currentTimeMillis();
        Long last = lastMessage.get(player.getUniqueId());

        if (last != null && now - last < cooldownMs) {
            event.setCancelled(true);
            double remaining = (cooldownMs - (now - last)) / 1000.0;
            player.sendMessage(MM.deserialize(
                    config.getString("cooldown.message", "<red>Attends <temps>s avant de renvoyer un message."),
                    Placeholder.unparsed("temps", String.format(Locale.ROOT, "%.1f", remaining))));
            return;
        }

        lastMessage.put(player.getUniqueId(), now);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastMessage.remove(event.getPlayer().getUniqueId());
    }

    /* ---------------- ./commande cliquable ---------------- */

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        FileConfiguration config = plugin.getConfig();

        if (!config.getBoolean("commandes-chat.actif", true)) return;
        if (!event.getPlayer().hasPermission("chatplugin.commande")) return;

        String prefix = config.getString("commandes-chat.prefixe-saisie", "./");
        String format = config.getString("commandes-chat.format", "<yellow>[/<commande>]</yellow>");
        String hover = config.getString("commandes-chat.survol", "<gray>Clique pour exécuter <yellow>/<commande>");
        String action = config.getString("commandes-chat.action", "run_command");
        List<String> allowed = config.getStringList("commandes-chat.commandes-autorisees")
                .stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();

        // Le préfixe doit être au début d'un mot : ./caisse
        Pattern pattern = Pattern.compile("(?<!\\S)" + Pattern.quote(prefix) + "([A-Za-z0-9_:-]+)");

        TextReplacementConfig replacement = TextReplacementConfig.builder()
                .match(pattern)
                .replacement((match, builder) -> {
                    String name = match.group(1).toLowerCase(Locale.ROOT);

                    // Commande non autorisée : on laisse le texte tel quel
                    if (!allowed.isEmpty() && !allowed.contains(name)) {
                        return builder;
                    }

                    Component component = MM.deserialize(format,
                            Placeholder.unparsed("commande", name));

                    Component hoverText = MM.deserialize(hover,
                            Placeholder.unparsed("commande", name));

                    ClickEvent click = action.equalsIgnoreCase("suggest_command")
                            ? ClickEvent.suggestCommand("/" + name)
                            : ClickEvent.runCommand("/" + name);

                    return component
                            .hoverEvent(HoverEvent.showText(hoverText))
                            .clickEvent(click);
                })
                .build();

        event.message(event.message().replaceText(replacement));
    }
}
