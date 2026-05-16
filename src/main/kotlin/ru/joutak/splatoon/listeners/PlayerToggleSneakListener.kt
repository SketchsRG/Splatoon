package ru.joutak.splatoon.listeners

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import ru.joutak.splatoon.SplatoonPlugin
import ru.joutak.splatoon.config.SplatoonSettings
import ru.joutak.splatoon.scripts.GameManager
import java.util.UUID

class PlayerToggleSneakListener : Listener {
    companion object {
        val tasks: MutableMap<UUID, Int> = mutableMapOf()
    }

    private fun getTeamFromMaterial(material: Material): Int? {
        return when (material) {
            Material.RED_CONCRETE, Material.RED_NETHER_BRICK_STAIRS, Material.RED_NETHER_BRICK_SLAB -> 0
            Material.YELLOW_CONCRETE, Material.RESIN_BRICK_STAIRS, Material.RESIN_BRICK_SLAB -> 1
            Material.GREEN_CONCRETE, Material.MOSSY_COBBLESTONE_STAIRS, Material.MOSSY_COBBLESTONE_SLAB -> 2
            Material.BLUE_CONCRETE, Material.OXIDIZED_CUT_COPPER_STAIRS, Material.OXIDIZED_CUT_COPPER_SLAB -> 3
            else -> null
        }
    }

    @EventHandler
    fun onSneakToggle(event: PlayerToggleSneakEvent) {
        val player = event.player
        val uuid = player.uniqueId

        val gameNow = GameManager.playerGame[uuid]
        if (gameNow == null) {
            val existing = tasks.remove(uuid)
            if (existing != null) {
                Bukkit.getScheduler().cancelTask(existing)
                player.removePotionEffect(PotionEffectType.SPEED)
                player.removePotionEffect(PotionEffectType.INVISIBILITY)
                player.removePotionEffect(PotionEffectType.REGENERATION)
            }
            return
        }

        if (!SplatoonSettings.sneakOnInkEnabled) {
            val existing = tasks.remove(uuid)
            if (existing != null) {
                Bukkit.getScheduler().cancelTask(existing)
                player.removePotionEffect(PotionEffectType.SPEED)
                player.removePotionEffect(PotionEffectType.INVISIBILITY)
                player.removePotionEffect(PotionEffectType.REGENERATION)
            }
            return
        }

        if (!event.isSneaking || tasks.containsKey(uuid)) {
            val taskId = tasks.remove(uuid)
            if (taskId != null) Bukkit.getScheduler().cancelTask(taskId)
            player.removePotionEffect(PotionEffectType.SPEED)
            player.removePotionEffect(PotionEffectType.INVISIBILITY)
            player.removePotionEffect(PotionEffectType.REGENERATION)
            if (!event.isSneaking) return
        }

        val task = Bukkit.getScheduler().runTaskTimer(SplatoonPlugin.instance, Runnable {
            val game = GameManager.playerGame[uuid] ?: run {
                val existing = tasks.remove(uuid)
                if (existing != null) {
                    Bukkit.getScheduler().cancelTask(existing)
                    player.removePotionEffect(PotionEffectType.SPEED)
                    player.removePotionEffect(PotionEffectType.INVISIBILITY)
                    player.removePotionEffect(PotionEffectType.REGENERATION)
                }
                return@Runnable
            }

            val team = game.commands[uuid] ?: return@Runnable

            val loc = player.location
            val step = SplatoonSettings.sneakOnInkScanStepBlocks
            val steps = SplatoonSettings.sneakOnInkScanSteps

            var onInk = false
            for (dx in -steps..steps) {
                for (dz in -steps..steps) {
                    val check = loc.clone().add(dx.toDouble() * step, -1.0, dz.toDouble() * step)
                    val blockMat = check.block.type
                    if (getTeamFromMaterial(blockMat) == team) {
                        onInk = true
                        break
                    }
                }
                if (onInk) break
            }

            if (onInk) {
                player.addPotionEffect(
                    PotionEffect(
                        PotionEffectType.SPEED,
                        SplatoonSettings.sneakOnInkEffectDurationTicks,
                        SplatoonSettings.sneakOnInkSpeedAmplifier,
                        false,
                        false,
                        true
                    )
                )

                if (SplatoonSettings.sneakOnInkInvisibilityAmplifier >= 0) {
                    player.addPotionEffect(
                        PotionEffect(
                            PotionEffectType.INVISIBILITY,
                            SplatoonSettings.sneakOnInkEffectDurationTicks,
                            SplatoonSettings.sneakOnInkInvisibilityAmplifier,
                            false,
                            false,
                            true
                        )
                    )
                }

                if (SplatoonSettings.sneakOnInkRegenerationAmplifier >= 0){
                    player.addPotionEffect(
                        PotionEffect(
                            PotionEffectType.REGENERATION,
                            SplatoonSettings.sneakOnInkEffectDurationTicks,
                            SplatoonSettings.sneakOnInkRegenerationAmplifier,
                            false,
                            false,
                            true
                        )
                    )
                }
            } else {
                // If they are sneaking but not on ink anymore, we should remove the effects early!
                player.removePotionEffect(PotionEffectType.SPEED)
                player.removePotionEffect(PotionEffectType.INVISIBILITY)
                player.removePotionEffect(PotionEffectType.REGENERATION)
            }
        }, 0L, SplatoonSettings.sneakOnInkTaskPeriodTicks)

        tasks[uuid] = task.taskId
    }
}
