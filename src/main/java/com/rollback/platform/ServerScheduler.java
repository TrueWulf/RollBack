package com.rollback.platform;

import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.function.Consumer;

public final class ServerScheduler {
    private final Plugin plugin;
    private final Server server;
    private final Object regionScheduler;
    private final Object globalScheduler;
    private final boolean folia;

    public ServerScheduler(Plugin plugin) {
        this.plugin = plugin;
        this.server = plugin.getServer();
        String mode = plugin.getConfig().getString("scheduler.mode", "auto");
        this.folia = resolveFolia(mode == null ? "auto" : mode);
        if (folia) {
            this.regionScheduler = invokeNoArgs(server, "getRegionScheduler");
            this.globalScheduler = invokeNoArgs(server, "getGlobalRegionScheduler");
            if (regionScheduler == null || globalScheduler == null) {
                throw new IllegalStateException("Folia scheduler was requested but is unavailable");
            }
        } else {
            this.regionScheduler = null;
            this.globalScheduler = null;
        }
    }

    public boolean isFolia() {
        return folia;
    }

    public String getPlatformName() {
        return folia ? "Folia" : server.getName();
    }

    public void runGlobal(Runnable task) {
        if (!folia) {
            server.getScheduler().runTask(plugin, task);
            return;
        }
        invokeExecute(globalScheduler, new Object[]{plugin}, task);
    }

    public void runAt(Location location, Runnable task) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        if (!folia) {
            server.getScheduler().runTask(plugin, task);
            return;
        }
        invokeExecute(regionScheduler, new Object[]{
                plugin,
                location.getWorld(),
                location.getBlockX() >> 4,
                location.getBlockZ() >> 4
        }, task);
    }

    private boolean resolveFolia(String configuredMode) {
        String mode = configuredMode.toLowerCase(Locale.ROOT);
        if ("bukkit".equals(mode) || "leaf".equals(mode) || "paper".equals(mode)) {
            return false;
        }
        if ("folia".equals(mode)) {
            return true;
        }
        String serverName = server.getName().toLowerCase(Locale.ROOT);
        String className = server.getClass().getName().toLowerCase(Locale.ROOT);
        if (serverName.contains("leaf") || className.contains("leaf")) {
            return false;
        }
        return serverName.equals("folia")
                || className.contains("regionizedserver")
                || className.contains("folia");
    }

    private Object invokeNoArgs(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            return method.invoke(target);
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }

    private void invokeExecute(Object target, Object[] prefix, Runnable task) {
        Method selected = null;
        Object callback = null;
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals("execute") || method.getParameterCount() != prefix.length + 1) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            boolean compatible = true;
            for (int index = 0; index < prefix.length; index++) {
                Object argument = prefix[index];
                if (argument == null || !box(parameterTypes[index]).isAssignableFrom(argument.getClass())) {
                    compatible = false;
                    break;
                }
            }
            Object candidateCallback = callbackFor(parameterTypes[prefix.length], task);
            if (candidateCallback == null) {
                compatible = false;
            }
            if (compatible) {
                selected = method;
                callback = candidateCallback;
                break;
            }
        }
        if (selected == null) {
            throw new IllegalStateException("Folia scheduler execute method was not found");
        }
        try {
            Object[] arguments = new Object[prefix.length + 1];
            System.arraycopy(prefix, 0, arguments, 0, prefix.length);
            arguments[prefix.length] = callback;
            selected.invoke(target, arguments);
        } catch (IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("Folia scheduler execute failed", exception);
        }
    }

    private Object callbackFor(Class<?> parameterType, Runnable task) {
        if (parameterType.isAssignableFrom(Runnable.class)) {
            return task;
        }
        if (parameterType.isAssignableFrom(Consumer.class)) {
            return (Consumer<Object>) ignored -> task.run();
        }
        return null;
    }

    private Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        return type;
    }
}
