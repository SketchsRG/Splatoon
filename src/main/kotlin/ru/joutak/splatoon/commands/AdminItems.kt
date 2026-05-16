package ru.joutak.splatoon.commands

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.CrossbowMeta
import org.bukkit.persistence.PersistentDataType
import ru.joutak.splatoon.SplatoonPlugin

object AdminItems {

    fun gun(team: Int = -1): ItemStack {
        val item = ItemStack(Material.CROSSBOW, 1)
        val meta = item.itemMeta
        val plugin = SplatoonPlugin.instance

        meta.displayName(Component.text("Сплат-пушка").color(TextColor.color(0xFF55FF)))
        meta.persistentDataContainer.set(NamespacedKey(plugin, "splatGun"), PersistentDataType.BOOLEAN, true)
        markAdmin(meta.persistentDataContainer, team)

        meta.setCustomModelData(ru.joutak.splatoon.config.SplatoonSettings.defaultSkin)

        // Держим арбалет визуально "заряженным" всегда, чтобы моделька не дёргалась.
        (meta as? CrossbowMeta)?.let { crossbow ->
            if (crossbow.chargedProjectiles.isEmpty()) {
                runCatching { crossbow.addChargedProjectile(ItemStack(Material.ARROW, 1)) }
            }
        }

        item.itemMeta = meta
        return item
    }

    fun bomb(team: Int = 0): ItemStack {
        val item = ItemStack(Material.GOLDEN_AXE, 1)
        val meta = item.itemMeta
        val plugin = SplatoonPlugin.instance

        meta.displayName(Component.text("Сплат-бомба").color(TextColor.color(0xFF55FF)))
        meta.persistentDataContainer.set(NamespacedKey(plugin, "Bomb"), PersistentDataType.BOOLEAN, true)
        markAdmin(meta.persistentDataContainer, team)

        item.itemMeta = meta
        return item
    }

    fun bacillus(team: Int = 0): ItemStack {
        val item = ItemStack(Material.AMETHYST_SHARD, 1)
        val meta = item.itemMeta
        val plugin = SplatoonPlugin.instance

        meta.displayName(Component.text("Бацилла").color(TextColor.color(0xFF55FF)))
        meta.persistentDataContainer.set(NamespacedKey(plugin, "Bacillus"), PersistentDataType.BOOLEAN, true)
        markAdmin(meta.persistentDataContainer, team)

        item.itemMeta = meta
        return item
    }

    private fun markAdmin(pdc: org.bukkit.persistence.PersistentDataContainer, team: Int) {
        val plugin = SplatoonPlugin.instance
        pdc.set(NamespacedKey(plugin, "splatoonAdmin"), PersistentDataType.BOOLEAN, true)
        pdc.set(NamespacedKey(plugin, "adminTeam"), PersistentDataType.INTEGER, team)
    }
}
