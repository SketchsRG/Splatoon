package ru.joutak.splatoon.scripts

import com.onarandombox.MultiverseCore.MultiverseCore
import org.bukkit.*
import org.bukkit.Bukkit.getWorld
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffectType
import ru.joutak.minigames.MiniGamesAPI
import ru.joutak.minigames.domain.GameInstance
import ru.joutak.minigames.managers.MatchmakingManager
import ru.joutak.splatoon.SplatoonPlugin
import ru.joutak.splatoon.config.SplatoonSettings
import java.io.File
import java.util.UUID

object GameManager {
    val playerGame = mutableMapOf<UUID, Game>()
    val arenas: MutableMap<String, World> = mutableMapOf()
    private val gamesByWorld: MutableMap<String, Game> = mutableMapOf()
    private val spectatingWorldByPlayer: MutableMap<UUID, String> = mutableMapOf()

    private val adminAmmoOverride: MutableMap<UUID, Pair<Int, Long>> = mutableMapOf()

    private val templateWorlds: MutableSet<String> = mutableSetOf()

    data class CeremonyBounds(
        val worldName: String,
        val minX: Double,
        val maxX: Double,
        val minZ: Double,
        val maxZ: Double,
        val safeLocation: Location
    )

    private val ceremonyBoundsByPlayer: MutableMap<UUID, CeremonyBounds> = mutableMapOf()

    private val playerSelectedTeam = mutableMapOf<UUID, Int>()

    private val playerSkin = mutableMapOf<UUID, Int>()

    fun setSkin(uuid: UUID, id: Int) {
        playerSkin[uuid] = id
    }

    fun getSkin(uuid: UUID): Int {
        return playerSkin[uuid] ?: SplatoonSettings.defaultSkin
    }

    fun registerTemplateWorld(worldName: String) {
        templateWorlds.add(worldName)
    }

    fun setCeremonyBounds(uuid: UUID, bounds: CeremonyBounds) {
        ceremonyBoundsByPlayer[uuid] = bounds
    }

    fun clearCeremonyBounds(uuid: UUID) {
        ceremonyBoundsByPlayer.remove(uuid)
    }

    fun getCeremonyBounds(uuid: UUID): CeremonyBounds? = ceremonyBoundsByPlayer[uuid]

    fun clearCeremonyBoundsForWorld(worldName: String) {
        val it = ceremonyBoundsByPlayer.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.value.worldName == worldName) it.remove()
        }
    }

    fun cleanupClonedWorld(worldName: String) {
        cleanupWorld(worldName)
    }

    fun cloneWorldFromTemplate(templateWorldName: String): World? {
        val multiverseCore = Bukkit.getPluginManager().getPlugin("Multiverse-Core") as? MultiverseCore ?: return null
        val template = Bukkit.getWorld(templateWorldName) ?: run {
            SplatoonPlugin.instance.logger.warning("Template world $templateWorldName is not loaded; can't clone ceremony.")
            return null
        }

        val mvTemplate = multiverseCore.mvWorldManager.getMVWorld(template.name) ?: run {
            SplatoonPlugin.instance.logger.warning("Template world $templateWorldName is not registered in Multiverse; can't clone ceremony.")
            return null
        }

        val worldName = nextWorldName(templateWorldName)
        cleanupWorld(worldName)

        try {
            multiverseCore.mvWorldManager.cloneWorld(template.name, worldName)
            if (multiverseCore.mvWorldManager.getMVWorld(worldName) == null) {
                multiverseCore.mvWorldManager.addWorld(
                    worldName,
                    mvTemplate.environment,
                    null,
                    mvTemplate.worldType,
                    true,
                    mvTemplate.generator
                )
            }
        } catch (e: Exception) {
            SplatoonPlugin.instance.logger.severe("World clone $worldName failed")
            e.printStackTrace()
            cleanupWorld(worldName)
            return null
        }

        val world = Bukkit.getWorld(worldName) ?: Bukkit.createWorld(WorldCreator(worldName)) ?: run {
            SplatoonPlugin.instance.logger.severe("Failed to load cloned world $worldName")
            cleanupWorld(worldName)
            return null
        }

        val mvWorld = multiverseCore.mvWorldManager.getMVWorld(worldName)
        if (mvWorld != null) {
            // Keep this compatible with older Multiverse versions: only use the stable APIs.
            runCatching { mvWorld.setTime("day") }
            runCatching { mvWorld.setEnableWeather(false) }
        }

        // Ceremony worlds should be safe even if template had different settings.
        runCatching { world.pvp = false }
        runCatching { world.setGameRule(GameRule.DO_MOB_SPAWNING, false) }
        runCatching { world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false) }
        runCatching { world.setGameRule(GameRule.DO_FIRE_TICK, false) }
        runCatching { world.setStorm(false) }
        runCatching { world.isThundering = false }

        arenas[worldName] = world
        return world
    }

    private fun disablePortalMakingCompat(mvWorld: Any) {
        // Multiverse-Core API differs between versions (MV2/MV5).
        // Try to disable portal creation without referencing optional enums at compile-time.
        runCatching {
            val m = mvWorld.javaClass.methods.firstOrNull { it.name == "allowPortalMaking" && it.parameterCount == 1 }
                ?: return@runCatching
            val p = m.parameterTypes[0]
            val arg: Any? = when {
                p == java.lang.Boolean.TYPE || p == java.lang.Boolean::class.java -> false
                p.isEnum -> p.enumConstants?.firstOrNull {
                    (it as? Enum<*>)?.name?.equals("NONE", ignoreCase = true) == true
                }
                else -> null
            }
            if (arg != null) m.invoke(mvWorld, arg)
        }

        // Older Multiverse versions used setPortalForm(enum).
        runCatching {
            val m = mvWorld.javaClass.methods.firstOrNull { it.name == "setPortalForm" && it.parameterCount == 1 }
                ?: return@runCatching
            val p = m.parameterTypes[0]
            val arg: Any? = if (p.isEnum) {
                p.enumConstants?.firstOrNull {
                    (it as? Enum<*>)?.name?.equals("NONE", ignoreCase = true) == true
                }
            } else null
            if (arg != null) m.invoke(mvWorld, arg)
        }
    }


    fun sendToLobby(player: Player) {
        val lobbyWorld = Bukkit.getWorld(SplatoonSettings.lobbyWorldName)
        val spawn = lobbyWorld?.spawnLocation ?: Bukkit.getWorlds()[0].spawnLocation

        player.scoreboard = Bukkit.getScoreboardManager().newScoreboard
        player.inventory.clear()
        player.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 20.0
        player.absorptionAmount = 0.0
        player.removePotionEffect(PotionEffectType.GLOWING)
        player.health = 20.0
        player.foodLevel = 20
        player.saturation = 20f
        player.activePotionEffects.forEach { effect ->
            player.removePotionEffect(effect.type)
        }
        player.teleport(spawn)
    }

    fun removePlayerFromGame(uuid: UUID) {
        val game = playerGame.remove(uuid) ?: return

        // Keep player stats for end-of-match results, but exclude the player from active match logic.
        game.markPlayerLeft(uuid)
        game.commands.remove(uuid)
    }

    fun getGame(player: Player): Game? = playerGame[player.uniqueId]

    fun getActiveGames(): List<Game> = gamesByWorld.values.toList()

    fun getGameByWorld(worldName: String): Game? = gamesByWorld[worldName]

    fun getSpectatingGame(player: Player): Game? {
        val world = spectatingWorldByPlayer[player.uniqueId] ?: return null
        return gamesByWorld[world]
    }

    fun getSpectatingWorld(playerId: UUID): String? = spectatingWorldByPlayer[playerId]

    fun setSpectating(playerId: UUID, worldName: String) {
        spectatingWorldByPlayer[playerId] = worldName
    }

    fun clearSpectating(playerId: UUID) {
        spectatingWorldByPlayer.remove(playerId)
    }

    fun setAdminAmmoOverride(uuid: UUID, team: Int, durationMs: Long) {
        adminAmmoOverride[uuid] = team to (System.currentTimeMillis() + durationMs)
    }

    fun getAdminAmmoTeam(uuid: UUID, baseTeam: Int): Int {
        val override = adminAmmoOverride[uuid]
        if (override != null) {
            if (System.currentTimeMillis() < override.second) return override.first
            adminAmmoOverride.remove(uuid)
        }
        return baseTeam
    }

    fun isLikelyCloneWorld(worldName: String): Boolean {
        for (template in templateWorlds) {
            val prefix = "${template}_"
            if (worldName.startsWith(prefix)) {
                val suffix = worldName.removePrefix(prefix)
                if (suffix.toIntOrNull() != null) return true
            }
        }
        return false
    }

    fun isLobbyWorld(world: World): Boolean {
        return world.name == SplatoonSettings.lobbyWorldName
    }

    fun cleanupOrphanedWorlds() {
        val loaded = Bukkit.getWorlds().map { it.name }.toSet()
        loaded.forEach { worldName ->
            if (isLikelyCloneWorld(worldName) && !arenas.containsKey(worldName)) {
                cleanupWorld(worldName)
            }
        }

        val container = Bukkit.getWorldContainer()
        val dirs = container.listFiles() ?: emptyArray()
        dirs.forEach { dir ->
            if (dir.isDirectory && isLikelyCloneWorld(dir.name) && !arenas.containsKey(dir.name)) {
                cleanupWorld(dir.name)
            }
        }
    }

    fun createGame(instance: GameInstance) {
        val multiverseCore = Bukkit.getPluginManager().getPlugin("Multiverse-Core") as? MultiverseCore
        if (multiverseCore == null) {
            SplatoonPlugin.instance.logger.severe("Multiverse-Core не найден. Невозможно создать игру.")
            return
        }

        val baseArenaId = (instance.config.meta["arenaId"] as? String) ?: instance.config.id
        val arenaSettings = SplatoonSettings.arenasById[baseArenaId]

        val templateWorldName = arenaSettings?.templateWorld
            ?: (instance.config.meta["world"] as? String)
            ?: SplatoonSettings.defaultTemplateWorld

        val template = Bukkit.getWorld(templateWorldName)
        if (template == null) {
            SplatoonPlugin.instance.logger.severe("Template world $templateWorldName not found")
            return
        }

        val mvWorld = multiverseCore.mvWorldManager.getMVWorld(template.name)
        if (mvWorld != null) {
            mvWorld.setTime("day")
            mvWorld.setEnableWeather(false)
            mvWorld.setDifficulty(Difficulty.PEACEFUL)
            mvWorld.setPVPMode(false)
            mvWorld.gameMode = GameMode.ADVENTURE
            mvWorld.hunger = true
            mvWorld.setAllowAnimalSpawn(false)
            mvWorld.setAllowMonsterSpawn(false)
            disablePortalMakingCompat(mvWorld)
        }

        val worldName = nextWorldName(templateWorldName)
        cleanupWorld(worldName)

        try {
            multiverseCore.mvWorldManager.cloneWorld(template.name, worldName)
        } catch (e: Exception) {
            SplatoonPlugin.instance.logger.severe("Не удалось клонировать мир $templateWorldName -> $worldName: ${e.message}")
            cleanupWorld(worldName)
            return
        }

        var world = getWorld(worldName)
        if (world == null) {
            try {
                world = Bukkit.createWorld(WorldCreator(worldName))
            } catch (_: Exception) {
            }
        }

        if (world == null) {
            SplatoonPlugin.instance.logger.severe("World clone $worldName failed")
            cleanupWorld(worldName)
            return
        }

        world.setGameRule(GameRule.FALL_DAMAGE, false)
        world.setGameRule(GameRule.DROWNING_DAMAGE, false)
        world.setGameRule(GameRule.FIRE_DAMAGE, false)

        arenas[worldName] = world

        val game = Game(worldName, baseArenaId, arenaSettings?.spawns ?: emptyList())

        // Snapshot tournament keys as early as possible (if instance provides them).
        for (teamIndex in 0..3) {
            val teamKey = runCatching { instance.getTournamentTeamKey(teamIndex) }.getOrNull()
            game.setTournamentTeamKey(teamIndex, teamKey)
        }

        gamesByWorld[worldName] = game

        val playersToRemove = mutableListOf<Player>()
        val teamsSnapshot = instance.teams.map { it.toList() }

        teamsSnapshot.forEachIndexed { teamIndex, teamPlayers ->
            teamPlayers.forEach { player ->
                val bukkitPlayer = Bukkit.getPlayer(player.name) ?: return@forEach
                playerGame[bukkitPlayer.uniqueId] = game
                game.commands[bukkitPlayer.uniqueId] = teamIndex
                game.paintedPerson[bukkitPlayer.uniqueId] = 0
                playersToRemove.add(bukkitPlayer)
            }
        }

        playersToRemove.forEach { p ->
            try {
                instance.removePlayer(p)
            } catch (_: Exception) {
            }
        }

        game.startGame(worldName)
    }

    private fun nextWorldName(templateWorldName: String): String {
        var key = 1
        while (true) {
            val worldName = "${templateWorldName}_$key"
            if (!arenas.containsKey(worldName)) return worldName
            key++
        }
    }

    private fun cleanupWorld(worldName: String) {
        val loadedWorld = Bukkit.getWorld(worldName)
        if (loadedWorld != null) {
            loadedWorld.players.toList().forEach { player ->
                sendToLobby(player)
            }
            Bukkit.unloadWorld(loadedWorld, false)
        }

        val multiverseCore = Bukkit.getPluginManager().getPlugin("Multiverse-Core") as? MultiverseCore
        if (multiverseCore != null) {
            try {
                multiverseCore.mvWorldManager.deleteWorld(worldName)
            } catch (_: Exception) {
            }
        }

        File(Bukkit.getWorldContainer(), worldName).deleteRecursively()
    }

    fun deleteGame(worldName: String, game: Game) {
        // Restore and remove spectators BEFORE world cleanup so their inventories/locations are not wiped.
        runCatching { game.forceRemoveAllSpectators(forceLobby = true) }

        game.commands.keys.forEach { uuid ->
            val player = Bukkit.getPlayer(uuid)
            if (player != null) {
                MatchmakingManager.removePlayer(player)
            }
        }

        cleanupWorld(worldName)

        playerGame.entries.removeAll { it.value == game }
        arenas.remove(worldName)
        gamesByWorld.remove(worldName)
        spectatingWorldByPlayer.entries.removeAll { it.value == worldName }
    }

    fun shutdownAllGames() {
        val games = gamesByWorld.values.toSet()
        games.forEach { game ->
            game.shutdownGame()
            deleteGame(game.worldName, game)
        }

        cleanupOrphanedWorlds()
    }

    fun getSelectedTeam(player: Player): Int {
        try{
            val apiTeam = MiniGamesAPI.getPlayerTeamInLobby(player)
            if (apiTeam != null) {
                return apiTeam
            }
        } catch (_: Exception) {}
        return playerSelectedTeam[player.uniqueId] ?: -1
    }

}
