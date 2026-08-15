package com.rollback.service

import org.bukkit.entity.Player

data class SelectionBounds(
    val world: String,
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val maxX: Int,
    val maxY: Int,
    val maxZ: Int,
)

object WorldEditSelectionService {
    data class Result(val bounds: SelectionBounds?, val reason: String? = null)

    fun selection(player: Player): SelectionBounds? = selectionResult(player).bounds

    fun selectionResult(player: Player): Result = try {
        val worldEdit = Class.forName("com.sk89q.worldedit.WorldEdit")
        val instance = worldEdit.getMethod("getInstance").invoke(null)
        val sessionManager = worldEdit.getMethod("getSessionManager").invoke(instance)
        val adapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter")
        val adapted = findStaticAdapter(adapter, player)
            ?: return Result(null, "WorldEdit BukkitAdapter.adapt(Player) is unavailable")
        val session = findCompatibleMethod(sessionManager, "get", adapted)
            ?: return Result(null, "WorldEdit session manager has no compatible get method")
        val selection = findNoArgMethod(session, "getSelection")?.invoke(session)
            ?: return Result(null, "No WorldEdit region is selected")
        val min = findNoArgMethod(selection, "getMinimumPoint")?.invoke(selection)
            ?: return Result(null, "WorldEdit region has no minimum point")
        val max = findNoArgMethod(selection, "getMaximumPoint")?.invoke(selection)
            ?: return Result(null, "WorldEdit region has no maximum point")
        val bounds = SelectionBounds(
            player.world.name,
            coordinate(min, "getBlockX"),
            coordinate(min, "getBlockY"),
            coordinate(min, "getBlockZ"),
            coordinate(max, "getBlockX"),
            coordinate(max, "getBlockY"),
            coordinate(max, "getBlockZ"),
        )
        Result(bounds)
    } catch (failure: Throwable) {
        Result(null, "${failure.javaClass.simpleName}: ${failure.message ?: "unknown error"}")
    }

    private fun findStaticAdapter(adapter: Class<*>, player: Player): Any? =
        adapter.methods.firstOrNull { method ->
            method.name == "adapt" && method.parameterCount == 1 && method.parameterTypes[0].isAssignableFrom(player.javaClass)
        }?.invoke(null, player)

    private fun findCompatibleMethod(target: Any, name: String, argument: Any): Any? =
        target.javaClass.methods.firstOrNull { method ->
            method.name == name && method.parameterCount == 1 && method.parameterTypes[0].isAssignableFrom(argument.javaClass)
        }?.invoke(target, argument)

    private fun findNoArgMethod(target: Any, name: String) =
        target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }

    private fun coordinate(point: Any, methodName: String): Int =
        (findNoArgMethod(point, methodName)?.invoke(point) as? Number)?.toInt()
            ?: error("WorldEdit point has no $methodName method")
}
