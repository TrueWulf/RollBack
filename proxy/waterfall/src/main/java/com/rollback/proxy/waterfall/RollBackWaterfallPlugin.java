package com.rollback.proxy.waterfall;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Plugin;

public final class RollBackWaterfallPlugin extends Plugin {
    @Override
    public void onEnable() {
        ProxyServer.getInstance().registerChannel("rollback:command");
        ProxyServer.getInstance().getPluginManager().registerCommand(this, new RollBackCommand());
    }

    private static final class RollBackCommand extends Command {
        private RollBackCommand() { super("rollback", null, "rb"); }

        @Override
        public void execute(CommandSender sender, String[] args) {
            if (!(sender instanceof ProxiedPlayer player) || player.getServer() == null) {
                sender.sendMessage(new TextComponent("Use /rb from a connected player."));
                return;
            }
            String command = args.length == 0 ? "rb help" : "rb " + String.join(" ", args);
            player.getServer().getInfo().sendData("rollback:command", command.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
