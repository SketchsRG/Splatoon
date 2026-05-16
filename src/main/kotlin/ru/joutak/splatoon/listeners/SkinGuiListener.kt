package ru.joutak.splatoon.listeners

import org.bukkit.NamespacedKey
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import ru.joutak.splatoon.SplatoonPlugin
import ru.joutak.splatoon.scripts.GameManager

class SkinGuiListener : Listener {

    @EventHandler
    fun onClick(e: InventoryClickEvent) {
        val titleStr = e.view.title
        
        if (titleStr == "Установить скин") {
            e.isCancelled = true

            val item = e.currentItem ?: return
            val meta = item.itemMeta ?: return
            
            if (!meta.hasCustomModelData()) return
            
            val id = meta.customModelData

            GameManager.setSkin(e.whoClicked.uniqueId, id)
            e.whoClicked.sendMessage("§aСкин установлен!")

            for (i in e.whoClicked.inventory.contents) {
                if (i == null) continue

                val iMeta = i.itemMeta ?: continue

                if (!iMeta.persistentDataContainer.has(NamespacedKey(SplatoonPlugin.instance, "splatGun"))) continue

                iMeta.setCustomModelData(id)
                i.itemMeta = iMeta
            }
            
            e.whoClicked.closeInventory()
        }
    }
}
