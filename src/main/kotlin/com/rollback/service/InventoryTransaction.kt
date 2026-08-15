package com.rollback.service

import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID

data class InventorySnapshot(
    val top: Array<ItemStack?>,
    val bottom: Array<ItemStack?>,
) {
    fun matches(topInventory: Inventory, bottomInventory: Inventory): Boolean =
        same(top, topInventory.contents) && same(bottom, bottomInventory.contents)

    fun apply(topInventory: Inventory, bottomInventory: Inventory) {
        topInventory.contents = top.copyOf()
        bottomInventory.contents = bottom.copyOf()
    }

    private fun same(expected: Array<ItemStack?>, actual: Array<ItemStack?>): Boolean =
        expected.size == actual.size && expected.indices.all { index ->
            val left = expected[index]
            val right = actual[index]
            left == null && right == null || left != null && right != null && left.isSimilar(right) && left.amount == right.amount
        }
}

data class InventoryTransaction(
    val id: String,
    val before: InventorySnapshot,
    val after: InventorySnapshot,
)

object InventorySnapshotCodec {
    fun encode(snapshot: InventorySnapshot): String = listOf(
        encodeItems(snapshot.top),
        encodeItems(snapshot.bottom),
    ).joinToString(".")

    fun decode(value: String?): InventorySnapshot? = runCatching {
        val parts = value?.split('.') ?: return null
        if (parts.size != 2) return null
        InventorySnapshot(decodeItems(parts[0]), decodeItems(parts[1]))
    }.getOrNull()

    fun transactionId(): String = UUID.randomUUID().toString().replace("-", "")

    private fun encodeItems(items: Array<ItemStack?>): String {
        val output = ByteArrayOutputStream()
        BukkitObjectOutputStream(output).use { stream -> stream.writeObject(items) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(output.toByteArray())
    }

    @Suppress("UNCHECKED_CAST")
    private fun decodeItems(value: String): Array<ItemStack?> {
        val bytes = Base64.getUrlDecoder().decode(value)
        BukkitObjectInputStream(ByteArrayInputStream(bytes)).use { stream ->
            return stream.readObject() as Array<ItemStack?>
        }
    }
}
