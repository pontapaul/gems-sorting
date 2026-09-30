package com.github.gemssorting;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** "/gems web": sends an operator a one-time login link for the group editor. */
final class GemsCommand implements TabExecutor {

    private final Auth auth;
    private final String publicUrl;

    GemsCommand(Auth auth, String publicUrl) {
        this.auth = auth;
        this.publicUrl = publicUrl;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Players get messages in their client's language (Italian or English); consoles in English.
        boolean it = sender instanceof Player p && p.locale().getLanguage().equals("it");
        if (args.length != 1 || !args[0].equalsIgnoreCase("web")) {
            sender.sendMessage(Component.text((it ? "Uso: /" : "Usage: /") + label + " web", NamedTextColor.GRAY));
            return true;
        }
        if (auth == null) {
            sender.sendMessage(Component.text(it
                    ? "L'interfaccia web è disattivata (web.enabled in config.yml)."
                    : "The web interface is disabled (web.enabled in config.yml).", NamedTextColor.RED));
            return true;
        }
        String url;
        if (sender instanceof Player player) {
            if (!player.isOp()) {
                sender.sendMessage(Component.text(it
                        ? "Solo gli operatori possono modificare i gruppi."
                        : "Only operators can edit the groups.", NamedTextColor.RED));
                return true;
            }
            url = publicUrl + "/login?t=" + auth.issueLink(player.getUniqueId(), player.getName());
        } else if (sender instanceof ConsoleCommandSender || sender instanceof RemoteConsoleCommandSender) {
            url = publicUrl + "/login?t=" + auth.issueLink(null, "Console");
        } else {
            sender.sendMessage(Component.text("Only players, the console and RCON can use this command.", NamedTextColor.RED));
            return true;
        }

        long minutes = Auth.LINK_VALIDITY.toMinutes();
        if (sender instanceof Player) {
            sender.sendMessage(Component.text(it ? "Editor dei gruppi: " : "Group editor: ", NamedTextColor.GRAY)
                    .append(Component.text(it ? "[apri nel browser]" : "[open in browser]",
                                    NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                            .clickEvent(ClickEvent.openUrl(url))
                            .hoverEvent(HoverEvent.showText(Component.text(publicUrl))))
                    .append(Component.text(it
                            ? " (link personale, valido " + minutes + " minuti e usabile una volta)"
                            : " (personal link, valid " + minutes + " minutes, single use)", NamedTextColor.DARK_GRAY)));
        } else {
            sender.sendMessage("Group editor login link (valid " + minutes + " minutes, single use): " + url);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return args.length == 1 && "web".startsWith(args[0].toLowerCase()) ? List.of("web") : List.of();
    }
}
