package fr.chatplugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class ChatPlugin extends JavaPlugin {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private BukkitTask task;
    private int index = 0;
    private ReactionManager reactions;

    public ReactionManager reactions() {
        return reactions;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reactions = new ReactionManager(this);
        getServer().getPluginManager().registerEvents(new ChatListener(this), this);
        getServer().getPluginManager().registerEvents(reactions, this);
        startAnnouncements();
        reactions.reload();
        getLogger().info("ChatPlugin activé.");
    }

    @Override
    public void onDisable() {
        if (task != null) task.cancel();
        if (reactions != null) reactions.stopAll();
    }

    /* ---------------- Annonces automatiques ---------------- */

    public void startAnnouncements() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (!getConfig().getBoolean("annonces.actif", true)) return;

        long ticks = Math.max(1, getConfig().getLong("annonces.intervalle", 300)) * 20L;
        task = Bukkit.getScheduler().runTaskTimer(this, this::broadcastNext, ticks, ticks);
    }

    public void broadcastNext() {
        List<String> messages = getConfig().getStringList("annonces.messages");
        if (messages.isEmpty()) return;

        String raw;
        if (getConfig().getBoolean("annonces.aleatoire", false)) {
            raw = messages.get(ThreadLocalRandom.current().nextInt(messages.size()));
        } else {
            if (index >= messages.size()) index = 0;
            raw = messages.get(index++);
        }

        String prefix = getConfig().getString("annonces.prefixe", "");
        Component component = MM.deserialize(prefix + raw);
        Bukkit.broadcast(component);
    }

    /* ---------------- Commande /chatplugin ---------------- */

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("chatplugin.admin")) {
            sender.sendMessage(msg("messages.pas-permission"));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(msg("messages.usage"));
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> {
                reloadConfig();
                index = 0;
                startAnnouncements();
                reactions.reload();
                sender.sendMessage(msg("messages.reload"));
            }
            case "annonce" -> {
                broadcastNext();
                sender.sendMessage(msg("messages.annonce-envoyee"));
            }
            case "reaction" -> {
                if (args.length < 2) {
                    sender.sendMessage(msg("messages.usage"));
                    return true;
                }
                if (args[1].equalsIgnoreCase("stop")) {
                    sender.sendMessage(msg(reactions.stopCurrent()
                            ? "messages.reaction-stop" : "messages.reaction-aucune"));
                    return true;
                }
                ReactionManager.Type type = ReactionManager.Type.fromKey(args[1]);
                if (type == null) {
                    sender.sendMessage(msg("messages.usage"));
                    return true;
                }
                sender.sendMessage(msg(reactions.start(type)
                        ? "messages.reaction-lancee" : "messages.reaction-impossible"));
            }
            default -> sender.sendMessage(msg("messages.usage"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("chatplugin.admin")) return List.of();
        List<String> list = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("reload", "annonce", "reaction")) {
                if (s.startsWith(args[0].toLowerCase())) list.add(s);
            }
            return list;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("reaction")) {
            for (String s : List.of("craft", "mot", "capitale", "stop")) {
                if (s.startsWith(args[1].toLowerCase())) list.add(s);
            }
            return list;
        }
        return List.of();
    }

    private Component msg(String path) {
        return MM.deserialize(getConfig().getString(path, ""));
    }
}
