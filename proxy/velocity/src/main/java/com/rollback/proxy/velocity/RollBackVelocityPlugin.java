package com.rollback.proxy.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import javax.inject.Inject;
import java.util.Optional;
import java.nio.file.Path;

@Plugin(id = "rollback-proxy", name = "RollBack Proxy", version = "0.5.0")
public final class RollBackVelocityPlugin {
    private static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create("rollback", "command");
    private final ProxyServer proxy;

    @Inject
    public RollBackVelocityPlugin(ProxyServer proxy, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        proxy.getChannelRegistrar().register(CHANNEL);
        proxy.getCommandManager().register("rollback", new RollBackCommand(proxy));
    }

    private static final class RollBackCommand implements SimpleCommand {
        private final ProxyServer proxy;

        private RollBackCommand(ProxyServer proxy) { this.proxy = proxy; }

        @Override
        public void execute(Invocation invocation) {
            if (!(invocation.source() instanceof Player player)) {
                invocation.source().sendPlainMessage("Use /rb from a connected player.");
                return;
            }
            Optional<com.velocitypowered.api.proxy.ServerConnection> server = player.getCurrentServer();
            if (server.isEmpty()) {
                invocation.source().sendPlainMessage("You are not connected to a backend server.");
                return;
            }
            String command = invocation.arguments().length == 0 ? "rb help" : "rb " + String.join(" ", invocation.arguments());
            server.get().sendPluginMessage(CHANNEL, command.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
