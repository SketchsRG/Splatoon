package ru.joutak.splatoon

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.plugin.java.JavaPlugin
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.domain.GameInstanceConfig
import ru.joutak.minigames.managers.MatchmakingManager
import ru.joutak.splatoon.commands.SplatoonCommand
import ru.joutak.splatoon.config.SplatoonSettings
import ru.joutak.splatoon.lang.Lang
import ru.joutak.splatoon.listeners.BacillusHitListener
import ru.joutak.splatoon.listeners.BoostPickupListener
import ru.joutak.splatoon.listeners.CeremonyMoveListener
import ru.joutak.splatoon.listeners.DamageGuardListener
import ru.joutak.splatoon.listeners.NaturalRegenerationListener
import ru.joutak.splatoon.listeners.PlayerMoveOnIceListener
import ru.joutak.splatoon.listeners.PlayerSessionListener
import ru.joutak.splatoon.listeners.PlayerToggleSneakListener
import ru.joutak.splatoon.listeners.PlayerUseListener
import ru.joutak.splatoon.listeners.ProjectileHitListener
import ru.joutak.splatoon.listeners.JumpPadListener
import ru.joutak.splatoon.listeners.LobbyGunPickupListener
import ru.joutak.splatoon.listeners.SkinGuiListener
import ru.joutak.splatoon.listeners.SpectatorWorldTeleportGuardListener
import ru.joutak.splatoon.listeners.SplatGunBowListener
import ru.joutak.splatoon.listeners.SplatGunProtectionListener
import ru.joutak.splatoon.scripts.GameManager
import ru.joutak.splatoon.scripts.LobbyGunStand
import java.io.File

class SplatoonPlugin : JavaPlugin() {
    companion object {
        @JvmStatic
        lateinit var instance: SplatoonPlugin
    }

    private fun loadConfig() {
        val fx = File(dataFolder, "config.yml")
        if (!fx.exists()) saveResource("config.yml", false)

        val config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(fx)
        SplatoonSettings.load(config, logger)

        GameManager.registerTemplateWorld(SplatoonSettings.defaultTemplateWorld)
        GameManager.registerTemplateWorld(SplatoonSettings.ceremonyTemplateWorld)
    }

    private fun loadArenas() {
        val arenasList = mutableListOf<GameInstanceConfig>()

        SplatoonSettings.arenas.forEach { arena ->
            arenasList.add(
                GameInstanceConfig(
                    id = arena.id,
                    teamCount = arena.teamCount,
                    playersPerTeam = arena.playersPerTeam,
                    meta = mapOf(
                        "world" to arena.templateWorld,
                        // MiniGamesAPI expands each config into a pool (parallel instances) using meta["pool_size"].
                        // This allows multiple matches on the same template world.
                        "pool_size" to arena.instances,
                        // Keep a stable arena id even if instance config ids change in future.
                        "arenaId" to arena.id
                    )
                )
            )
            GameManager.registerTemplateWorld(arena.templateWorld)
        }

        MatchmakingManager.loadInstances(arenasList)
    }

    override fun onEnable() {
        instance = this

        loadConfig()
        Lang.load(this)
        MiniGamesCore.initialize(this)
        loadArenas()

        GameManager.cleanupOrphanedWorlds()

        Bukkit.getPluginManager().registerEvents(PlayerToggleSneakListener(), this)
        Bukkit.getPluginManager().registerEvents(PlayerUseListener(this), this)
        Bukkit.getPluginManager().registerEvents(SplatGunBowListener(this), this)
        Bukkit.getPluginManager().registerEvents(ProjectileHitListener(), this)
        Bukkit.getPluginManager().registerEvents(DamageGuardListener(), this)
        Bukkit.getPluginManager().registerEvents(NaturalRegenerationListener(), this)
        Bukkit.getPluginManager().registerEvents(BacillusHitListener(this), this)
        Bukkit.getPluginManager().registerEvents(SplatGunProtectionListener(this), this)
        Bukkit.getPluginManager().registerEvents(PlayerSessionListener(), this)
        Bukkit.getPluginManager().registerEvents(SpectatorWorldTeleportGuardListener(), this)
        Bukkit.getPluginManager().registerEvents(CeremonyMoveListener(), this)
        Bukkit.getPluginManager().registerEvents(BoostPickupListener(), this)
        Bukkit.getPluginManager().registerEvents(PlayerMoveOnIceListener(), this)
        Bukkit.getPluginManager().registerEvents(JumpPadListener(), this)
        Bukkit.getPluginManager().registerEvents(LobbyGunPickupListener(), this)
        Bukkit.getPluginManager().registerEvents(SkinGuiListener(), this)


        val cmd = getCommand("splatoon")
        if (cmd != null) {
            val executor = SplatoonCommand(this)
            cmd.setExecutor(executor)
            cmd.tabCompleter = executor
        }

        server.scheduler.runTaskLater(this, Runnable {
            val lobbyWorld = Bukkit.getWorld(SplatoonSettings.lobbyWorldName)
            if (lobbyWorld != null) {
                val locations = SplatoonSettings.lobbyGunLocations.mapNotNull {coords ->
                    if(coords.size >= 3){
                        Location(lobbyWorld, coords[0], coords[1], coords[2])
                    } else null
                }
                if (locations.isNotEmpty()) {
                    LobbyGunStand.spawnAll(locations)
                } else {
                    logger.info(Lang.get("lobby.no_guns"))
                }
            } else {
                logger.warning(Lang.get("lobby.world_not_found",
                    "world" to SplatoonSettings.lobbyWorldName
                ))            }
        }, 20L)

        logger.info("Плагин ${pluginMeta.name} версии ${pluginMeta.version} включен!")

        server.scheduler.runTaskTimer(this, Runnable {
            val instance = MatchmakingManager.pollReady()
            if (instance != null) {
                logger.info(Lang.get("match.ready"))
                GameManager.createGame(instance)
            }
        }, 20L, 20L)
    }

    override fun onDisable() {
        GameManager.shutdownAllGames()

        LobbyGunStand.removeAll()
    }
}
