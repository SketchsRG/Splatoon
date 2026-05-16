package ru.joutak.splatoon.scripts

import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.GameRule
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.EntityType
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.CrossbowMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import org.bukkit.scoreboard.Criteria
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Objective
import org.bukkit.scoreboard.Scoreboard
import org.bukkit.scoreboard.Team
import org.bukkit.util.Vector
import ru.joutak.minigames.MiniGamesAPI
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.managers.MatchmakingManager
import ru.joutak.minigames.results.model.MatchContext
import ru.joutak.minigames.results.model.MatchResult
import ru.joutak.minigames.results.model.Metric
import ru.joutak.minigames.results.model.PlayerResult
import ru.joutak.minigames.results.model.TeamResult
import ru.joutak.minigames.tournament.TournamentManager
import ru.joutak.splatoon.SplatoonPlugin
import ru.joutak.splatoon.config.SpawnPoint
import ru.joutak.splatoon.config.SplatoonSettings
import java.time.Duration
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.random.Random
import io.papermc.paper.scoreboard.numbers.NumberFormat
import ru.joutak.splatoon.lang.Lang

class Game(var worldName: String, val arenaId: String, private val spawns: List<SpawnPoint>) {

    // Results (shared DB via MiniGamesAPI)
    val matchId: UUID = UUID.randomUUID()
    val startedAtMs: Long = System.currentTimeMillis()
    @Volatile
    private var resultsSent: Boolean = false

    /**
     * Tournament-only mapping: teamIndex -> tournament team_key.
     * Snapshot is provided by [GameManager] at match start.
     */
    private val tournamentTeamKeysByIndex: MutableMap<Int, String?> = mutableMapOf()

    fun setTournamentTeamKey(teamIndex: Int, teamKey: String?) {
        tournamentTeamKeysByIndex[teamIndex] = teamKey
    }

    private fun getTeamKey(teamId: Int): String {
        val key = tournamentTeamKeysByIndex[teamId]
        return if (!key.isNullOrBlank()) key else "team_${teamId}"
    }

    val paintedCommand: MutableMap<Int, Int> = mutableMapOf(0 to 0, 1 to 0, 2 to 0, 3 to 0)
    var paintedPerson: MutableMap<UUID, Int> = mutableMapOf()
    val kills: MutableMap<UUID, Int> = mutableMapOf()

    val commandColors: Map<Int, Material> = mapOf(
        0 to Material.RED_CONCRETE,
        3 to Material.BLUE_CONCRETE,
        2 to Material.GREEN_CONCRETE,
        1 to Material.YELLOW_CONCRETE
    )
    var commands: MutableMap<UUID, Int> = mutableMapOf()

    private val playerNames: MutableMap<UUID, String> = mutableMapOf()
    private val playerTeamsSnapshot: MutableMap<UUID, Int> = mutableMapOf()
    private val playerJoinedAtMs: MutableMap<UUID, Long> = mutableMapOf()
    private val playerLeftAtMs: MutableMap<UUID, Long> = mutableMapOf()

    val layingBoosts = mutableMapOf<List<Double>, ItemDisplay>()
    private val playerPreviousSpawns: MutableMap<UUID, SpawnPoint> = mutableMapOf()
    private var globalPreviousSpawn: SpawnPoint? = null

    private var countdownTask: BukkitTask? = null
    private var gameTimerTask: BukkitTask? = null
    private var boostTimerTask: BukkitTask? = null
    private var scoreboardUpdateTask: BukkitTask? = null
    private var actionBarTask: BukkitTask? = null

    private var endingCleanupTask: BukkitTask? = null

    private var ceremonyTask: BukkitTask? = null
    private var ceremonyWorldName: String? = null
    private var pendingMatchResult: MatchResult? = null

    fun getSpectatorExpectedWorldName(): String = ceremonyWorldName ?: worldName


    private var countdownLeft: Int? = null


    private var cleanupStarted: Boolean = false

    private var timeLeft = 0
    private val totalTime = SplatoonSettings.gameDurationSeconds
    private val bossBarsByTeam: MutableMap<Int, BossBar> = mutableMapOf()
    private var spectatorBossBar: BossBar? = null
    private var activeTeams: List<Int> = listOf()

    private val playerScoreboards: MutableMap<UUID, Scoreboard> = mutableMapOf()
    private val playerObjectives: MutableMap<UUID, Objective> = mutableMapOf()

    private val spectators: MutableSet<UUID> = mutableSetOf()
    private val spectatorBackups: MutableMap<UUID, SpectatorBackup> = mutableMapOf()

    private data class SpectatorBackup(
        val location: Location,
        val gameMode: GameMode,
        val allowFlight: Boolean,
        val isFlying: Boolean,
        val maxHealthBase: Double,
        val inventoryContents: Array<ItemStack?>,
        val armorContents: Array<ItemStack?>,
        val extraContents: Array<ItemStack?>,
        val level: Int,
        val exp: Float,
        val health: Double,
        val foodLevel: Int,
        val saturation: Float,
        val absorption: Double,
        val potionEffects: List<PotionEffect>,
        val scoreboard: Scoreboard
    )

    fun getActivePlayerCount(): Int = commands.size
    fun getSpectatorCount(): Int = spectators.size
    fun getTimeLeftSeconds(): Int = timeLeft


    fun getPhaseName(): String = when {
        cleanupStarted -> "CLEANUP"
        ended -> "ENDING"
        gameTimerTask != null -> "RUNNING"
        countdownTask != null -> "COUNTDOWN"
        else -> "WAITING"
    }

    fun adminSkipPhase(): String {
        return when (getPhaseName()) {
            "COUNTDOWN" -> {
                forceCountdownStartNow()
                "§aCOUNTDOWN → RUNNING"
            }
            "RUNNING" -> {
                endGame(worldName)
                "§aRUNNING → ENDING"
            }
            "ENDING" -> {
                forceCleanupNow()
                "§aENDING → CLEANUP"
            }
            else -> "§eНечего скипать (phase=${getPhaseName()})."
        }
    }

    fun adminSkipSeconds(seconds: Int): String {
        if (seconds <= 0) return "§cСекунды должны быть > 0"
        return when (getPhaseName()) {
            "COUNTDOWN" -> {
                val cur = countdownLeft ?: return "§eCountdown уже завершён."
                countdownLeft = (cur - seconds).coerceAtLeast(0)
                if (countdownLeft == 0) {
                    forceCountdownStartNow()
                    "§aCountdown пропущен."
                } else {
                    "§aCountdown: -$seconds сек (осталось $countdownLeft)."
                }
            }
            "RUNNING" -> {
                timeLeft -= seconds
                if (timeLeft <= 0) {
                    timeLeft = 0
                    endGame(worldName)
                    "§aВремя матча пропущено до конца."
                } else {
                    updateBossBar()
                    updateAllPlayerScoreboards()
                    "§aВремя матча: -$seconds сек (осталось $timeLeft)."
                }
            }
            "ENDING" -> {
                forceCleanupNow()
                "§aENDING: пропущено."
            }
            else -> "§eСейчас нельзя скипать секунды (phase=${getPhaseName()})."
        }
    }

    fun adminSetTimeLeft(seconds: Int): String {
        if (seconds < 0) return "§cСекунды должны быть >= 0"
        if (getPhaseName() != "RUNNING") return "§eКоманда доступна только в RUNNING (phase=${getPhaseName()})."
        timeLeft = seconds
        if (timeLeft <= 0) {
            timeLeft = 0
            endGame(worldName)
            return "§aВремя выставлено в 0 → ENDING."
        }
        updateBossBar()
        updateAllPlayerScoreboards()
        return "§aВремя выставлено: $timeLeft сек."
    }

    fun adminAddTime(deltaSeconds: Int): String {
        if (deltaSeconds == 0) return "§eDelta = 0"
        if (getPhaseName() != "RUNNING") return "§eКоманда доступна только в RUNNING (phase=${getPhaseName()})."
        timeLeft = (timeLeft + deltaSeconds).coerceAtLeast(0)
        if (timeLeft <= 0) {
            timeLeft = 0
            endGame(worldName)
            return "§aВремя стало 0 → ENDING."
        }
        updateBossBar()
        updateAllPlayerScoreboards()
        val sign = if (deltaSeconds > 0) "+" else ""
        return "§aВремя: ${sign}${deltaSeconds} сек (осталось $timeLeft)."
    }

    private fun forceCountdownStartNow() {
        val w = Bukkit.getWorld(worldName) ?: return
        if (ended) return
        if (gameTimerTask != null) return
        if (countdownTask == null) return
        countdownLeft = 0
        // Start immediately on main thread
        Bukkit.getScheduler().runTask(SplatoonPlugin.instance, Runnable {
            if (ended) return@Runnable
            if (gameTimerTask != null) return@Runnable
            if (countdownTask == null) return@Runnable
            doCountdownStartNow(w)
        })
    }

    private fun forceCleanupNow() {
        if (!ended) return
        finishCleanupNow(force = true)
    }

    var totalPaintableBlocks: Int = 0

    val ammoOverride: MutableMap<UUID, Pair<Int, Long>> = mutableMapOf()

    val spawnProtectedUntil: MutableMap<UUID, Long> = mutableMapOf()
    private val spawnProtectedOrigin: MutableMap<UUID, Vector> = mutableMapOf()
    private val spawnProtectionMoved: MutableMap<UUID, Boolean> = mutableMapOf()

    val maxInkHp: Int = SplatoonSettings.inkMaxHp
    private val inkHp: MutableMap<UUID, Int> = mutableMapOf()

    private val inkRegenCarry: MutableMap<UUID, Double> = mutableMapOf()
    private val inkLastDamageAt: MutableMap<UUID, Long> = mutableMapOf()

    private val hitMarkerActionbarTicks = 30
    private val actionBarOverlayUntilMs: MutableMap<UUID, Long> = mutableMapOf()
    private val actionBarOverlayText: MutableMap<UUID, Component> = mutableMapOf()

    private var ended = false

    fun shutdownGame() {
        // Restore spectators before any world cleanup happens.
        forceRemoveAllSpectators(forceLobby = true)

        gameTimerTask?.cancel()
        countdownTask?.cancel()
        endingCleanupTask?.cancel()
        endingCleanupTask = null
        scoreboardUpdateTask?.cancel()
        boostTimerTask?.cancel()
        actionBarTask?.cancel()

        removeBossBar()
        clearScoreboards()

        ammoOverride.clear()
        spawnProtectedUntil.clear()
        spawnProtectedOrigin.clear()
        spawnProtectionMoved.clear()
        inkHp.clear()
        inkRegenCarry.clear()
        inkLastDamageAt.clear()
        actionBarOverlayUntilMs.clear()
        actionBarOverlayText.clear()

        playerNames.clear()
        playerTeamsSnapshot.clear()
        playerJoinedAtMs.clear()
        playerLeftAtMs.clear()

        val emptyScoreboard = Bukkit.getScoreboardManager().newScoreboard
        val lobbyWorld = Bukkit.getWorld(SplatoonSettings.lobbyWorldName)
        val spawn = lobbyWorld?.spawnLocation ?: Bukkit.getWorlds()[0].spawnLocation

        commands.keys.forEach { playerId ->
            val player = Bukkit.getPlayer(playerId) ?: return@forEach
            player.scoreboard = emptyScoreboard
            player.inventory.clear()
            restoreVanillaHealth(player)
            player.foodLevel = 20
            player.saturation = 20f
            player.activePotionEffects.forEach { effect ->
                player.removePotionEffect(effect.type)
            }
            player.teleport(spawn)
        }
    }

    fun pushActionBarOverlay(uuid: UUID, component: Component, durationTicks: Int = hitMarkerActionbarTicks) {
        if (durationTicks <= 0) return
        actionBarOverlayText[uuid] = component
        actionBarOverlayUntilMs[uuid] = System.currentTimeMillis() + durationTicks * 50L
    }

    fun startGame(worldName: String) {
        activeTeams = commands.values.toSet().sorted()

        // Countdown runs before we create a new scoreboard objective. Clear any stale sidebar from previous rounds now.
        val emptyScoreboard = Bukkit.getScoreboardManager().newScoreboard

        // Snapshot players for results (including possible leavers).
        val joinAt = startedAtMs
        commands.forEach { (uuid, team) ->
            playerTeamsSnapshot[uuid] = team
            playerJoinedAtMs.putIfAbsent(uuid, joinAt)
            playerLeftAtMs.remove(uuid)
            val p = Bukkit.getPlayer(uuid)
            if (p != null) playerNames[uuid] = p.name
        }

        // Snapshot tournament team_key for each teamId (for results DB metrics).
        snapshotTournamentTeamKeys()

        commands.keys.forEach { uuid ->
            inkHp[uuid] = maxInkHp
            inkRegenCarry.remove(uuid)
            inkLastDamageAt.remove(uuid)
            val player = Bukkit.getPlayer(uuid) ?: return@forEach
            player.scoreboard = emptyScoreboard
            player.inventory.clear()
            ensureInkHealth(player)
            syncHealthBar(player)
            teleportToSpawn(player)
            setSpawnProtection(player, SplatoonSettings.spawnProtectionAfterRespawnSeconds * 1000L)
        }

        startCountdown(worldName)
    }

    private fun snapshotTournamentTeamKeys() {
        val tournamentEnabled = runCatching { MiniGamesAPI.config.get(ConfigKeys.TOURNAMENT_ENABLED) }
            .getOrDefault(false)
        if (!tournamentEnabled) return

        for ((uuid, teamId) in playerTeamsSnapshot) {
            if (!tournamentTeamKeysByIndex[teamId].isNullOrBlank()) continue
            val key = TournamentManager.getCachedTeamKey(uuid) ?: continue
            if (key.isBlank()) continue
            tournamentTeamKeysByIndex[teamId] = key
        }
    }

    fun markPlayerLeft(uuid: UUID) {
        if (!playerJoinedAtMs.containsKey(uuid)) {
            playerJoinedAtMs[uuid] = startedAtMs
        }

        val team = playerTeamsSnapshot[uuid] ?: commands[uuid]
        if (team != null) playerTeamsSnapshot[uuid] = team

        if (!playerNames.containsKey(uuid)) {
            val p = Bukkit.getPlayer(uuid)
            if (p != null) playerNames[uuid] = p.name
        }

        playerLeftAtMs.putIfAbsent(uuid, System.currentTimeMillis())
    }

    fun endGame(worldName: String) {
        if (ended) return
        ended = true

        // Keep spectators for the ceremony (they will be teleported into ceremony world).

        // Stop match tasks
        countdownLeft = null
        countdownTask?.cancel(); countdownTask = null
        gameTimerTask?.cancel(); gameTimerTask = null
        boostTimerTask?.cancel(); boostTimerTask = null
        scoreboardUpdateTask?.cancel(); scoreboardUpdateTask = null
        actionBarTask?.cancel(); actionBarTask = null

        // Remove HUD
        removeBossBar()
        clearScoreboards()

        val winnerTeam = determineWinner()
        val placementByTeam = computePlacementByTeam()

        // Build result now, but record it only after ceremony (so tournament kick happens after the ceremony).
        pendingMatchResult = buildMatchResult(winnerTeam, placementByTeam)

        val ceremonyStarted = tryStartCeremony(placementByTeam)
        showWinnerAnnouncement(winnerTeam, ceremonyStarted)

        if (ceremonyStarted) {
            return
        }

        // No ceremony configured / failed to start -> do not keep spectators in the match world.
        forceRemoveAllSpectators(forceLobby = true)

        // No ceremony configured / failed to start -> record right away and return to lobby after delay
        pendingMatchResult?.let { recordMatchResultIfNeeded(it) }

        endingCleanupTask?.cancel(); endingCleanupTask = null
        val delayTicks = (SplatoonSettings.returnToLobbyDelaySeconds.coerceAtLeast(0) * 20).toLong()
        endingCleanupTask = Bukkit.getScheduler().runTaskLater(SplatoonPlugin.instance, Runnable {
            finishCleanupNow(force = false)
        }, delayTicks)
    }


    private fun finishCleanupNow(force: Boolean) {
        // Always restore/teleport spectators before any world cleanup happens.
        forceRemoveAllSpectators(forceLobby = true)

        if (cleanupStarted) return
        cleanupStarted = true

        endingCleanupTask?.cancel(); endingCleanupTask = null
        ceremonyTask?.cancel(); ceremonyTask = null

        val lobbyWorld = Bukkit.getWorld(SplatoonSettings.lobbyWorldName)
        if (lobbyWorld == null) {
            SplatoonPlugin.instance.logger.warning("Lobby world '${SplatoonSettings.lobbyWorldName}' is not loaded. Can't cleanup game.")
            return
        }

        // Teleport participants to lobby
        val lobbyLoc = lobbyWorld.spawnLocation
        val emptyScoreboard = Bukkit.getScoreboardManager().newScoreboard
        commands.keys.forEach { uuid ->
            GameManager.clearCeremonyBounds(uuid)
            val player = Bukkit.getPlayer(uuid) ?: return@forEach
            player.scoreboard = emptyScoreboard
            player.inventory.clear()
            player.activePotionEffects.forEach { eff -> player.removePotionEffect(eff.type) }
            player.foodLevel = 20
            player.saturation = 20f
            player.fireTicks = 0
            player.health = player.maxHealth
            player.teleport(lobbyLoc)
        }

        // Cleanup ceremony world (if any)
        val ceremonyWorldNameSnapshot = ceremonyWorldName
        if (ceremonyWorldNameSnapshot != null) {
            GameManager.clearCeremonyBoundsForWorld(ceremonyWorldNameSnapshot)
            GameManager.cleanupClonedWorld(ceremonyWorldNameSnapshot)
            ceremonyWorldName = null
        }

        // Remove game instance
        GameManager.deleteGame(worldName, this)
        pendingMatchResult = null

        removeBossBar()
        clearScoreboards()
    }


    private fun computeTeamScorePercent(teamId: Int): Double {
        val blocks = (paintedCommand[teamId] ?: 0).coerceAtLeast(0)
        return if (totalPaintableBlocks > 0) blocks.toDouble() * 100.0 / totalPaintableBlocks.toDouble() else blocks.toDouble()
    }

    private fun normalizePlacementsAllTeams(raw: Map<Int, Int>): Map<Int, Int> {
        val out = mutableMapOf<Int, Int>()
        val usedPlaces = mutableSetOf<Int>()

        // Keep only valid unique placements for teams 0..3.
        for (teamId in 0..3) {
            val place = raw[teamId] ?: continue
            if (place !in 1..4) continue
            if (usedPlaces.contains(place)) continue
            out[teamId] = place
            usedPlaces.add(place)
        }

        val missingTeams = (0..3).filter { it !in out }
        val remainingPlaces = (1..4).filter { it !in usedPlaces }.toMutableList()

        // Fill missing with remaining unique places (stable order).
        missingTeams.forEachIndexed { idx, teamId ->
            out[teamId] = remainingPlaces.getOrNull(idx) ?: 4
        }

        return out
    }

    private fun normalizePlacementsForTeams(teamIds: List<Int>, raw: Map<Int, Int>): Map<Int, Int> {
        if (teamIds.isEmpty()) return emptyMap()

        val ordered = teamIds.sortedWith(
            compareBy<Int> {
                raw[it]?.takeIf { p -> p in 1..teamIds.size } ?: Int.MAX_VALUE
            }.thenBy { it }
        )

        val out = mutableMapOf<Int, Int>()
        ordered.forEachIndexed { idx, teamId ->
            out[teamId] = idx + 1
        }
        return out
    }

    private fun resolveMatchContext(): MatchContext? {
        val eventId = runCatching { MiniGamesAPI.config.get(ConfigKeys.TOURNAMENT_EVENT_ID).trim() }
            .getOrDefault("")
        val stage = runCatching { MiniGamesAPI.config.get(ConfigKeys.TOURNAMENT_STAGE).trim() }
            .getOrDefault("")

        if (eventId.isBlank() || stage.isBlank()) return null
        return MatchContext(eventId = eventId, stage = stage)
    }

    private fun buildMatchResult(winnerTeam: Int, placementByTeam: Map<Int, Int>): MatchResult {
        val now = System.currentTimeMillis()

        // IMPORTANT: include only non-empty teams in MatchResult.teams.
        // This prevents Elo/recalc from treating empty teams as real opponents.
        val participatingTeams = playerTeamsSnapshot.values.toSet().ifEmpty { activeTeams.toSet() }.toList().sorted()
        val placements = normalizePlacementsForTeams(participatingTeams, placementByTeam)

        val teamResults = participatingTeams.map { teamId ->
            val percent = computeTeamScorePercent(teamId)
            val killsTotal = playerTeamsSnapshot
                .filterValues { it == teamId }
                .keys
                .sumOf { kills[it] ?: 0 }
            val place = placements[teamId] ?: (placements.values.maxOrNull() ?: 1)
            TeamResult(
                teamId = teamId,
                placement = place,
                isWinner = place == 1,
                score = percent,
                metrics = listOf(
                    Metric.text("team_key", getTeamKey(teamId)),
                    Metric.real("paint_percent", percent),
                    Metric.int("kills", killsTotal.toLong()),
                ),
            )
        }

        val playerResults = playerTeamsSnapshot.keys.map { uuid ->
            val teamId = playerTeamsSnapshot[uuid] ?: commands[uuid] ?: 0
            val name = playerNames[uuid] ?: Bukkit.getOfflinePlayer(uuid).name
            val isWinner = (placements[teamId] ?: 4) == 1
            PlayerResult(
                playerUuid = uuid,
                playerName = name,
                teamId = teamId,
                isWinner = isWinner,
                joinedAtMs = playerJoinedAtMs[uuid],
                leftAtMs = playerLeftAtMs[uuid],
                metrics = listOf(
                    Metric.int("paint_contribution", (paintedPerson[uuid] ?: 0).toLong()),
                    Metric.int("kills", (kills[uuid] ?: 0).toLong()),
                ),
            )
        }

        return MatchResult(
            matchId = matchId,
            // Will be overridden by MiniGamesAPI according to config (mode.name).
            modeKey = "splatoon",
            mapKey = arenaId,
            startedAtMs = startedAtMs,
            endedAtMs = now,
            context = resolveMatchContext(),
            teams = teamResults,
            players = playerResults,
        )
    }

    private fun recordMatchResultIfNeeded(result: MatchResult) {
        if (resultsSent) return
        resultsSent = true
        try {
            MiniGamesAPI.recordMatchResult(result)
        } catch (t: Throwable) {
            SplatoonPlugin.instance.logger.warning("Failed to record match result: ${t.message}")
            t.printStackTrace()
        }
    }

    private fun tryStartCeremony(placementByTeam: Map<Int, Int>): Boolean {
        if (!SplatoonSettings.ceremonyEnabled) return false

        val podiums = SplatoonSettings.ceremonyPodiumsByPlace
        if (podiums.size < 4) {
            SplatoonPlugin.instance.logger.warning("Ceremony is enabled, but ceremony.podiums has less than 4 entries. Skipping ceremony.")
            return false
        }

        val ceremonyWorld = GameManager.cloneWorldFromTemplate(SplatoonSettings.ceremonyTemplateWorld)
        if (ceremonyWorld == null) {
            SplatoonPlugin.instance.logger.warning("Ceremony is enabled, but template world '${SplatoonSettings.ceremonyTemplateWorld}' couldn't be cloned. Skipping ceremony.")
            return false
        }

        ceremonyWorldName = ceremonyWorld.name

        // Make ceremony world safe
        runCatching {
            ceremonyWorld.pvp = false
            ceremonyWorld.setGameRule(GameRule.DO_MOB_SPAWNING, false)
            ceremonyWorld.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false)
            ceremonyWorld.setGameRule(GameRule.DO_FIRE_TICK, false)
            ceremonyWorld.setGameRule(GameRule.FALL_DAMAGE, false)
            ceremonyWorld.setGameRule(GameRule.KEEP_INVENTORY, true)
            ceremonyWorld.setGameRule(GameRule.NATURAL_REGENERATION, true)
        }

        val teamByPlace = placementByTeam.entries.associate { (team, place) -> place to team }

        for (place in 1..4) {
            val team = teamByPlace[place] ?: continue
            val podium = podiums[place] ?: continue

            val xMinBlock = minOf(podium.minX, podium.maxX)
            val xMaxBlock = maxOf(podium.minX, podium.maxX)
            val zMinBlock = minOf(podium.minZ, podium.maxZ)
            val zMaxBlock = maxOf(podium.minZ, podium.maxZ)

            val allowedMinX = xMinBlock.toDouble()
            val allowedMaxX = (xMaxBlock + 1).toDouble()
            val allowedMinZ = zMinBlock.toDouble()
            val allowedMaxZ = (zMaxBlock + 1).toDouble()

            val spawnLoc = podium.spawn?.let {
                Location(ceremonyWorld, it.x, it.y, it.z, it.yaw ?: 0f, it.pitch ?: 0f)
            } ?: Location(
                ceremonyWorld,
                xMinBlock + (xMaxBlock - xMinBlock + 1) / 2.0,
                (maxOf(podium.minY, podium.maxY) + 1).toDouble(),
                zMinBlock + (zMaxBlock - zMinBlock + 1) / 2.0,
                0f,
                0f
            )

            commands.filterValues { it == team }.keys.forEach { uuid ->
                val player = Bukkit.getPlayer(uuid) ?: return@forEach
                resetPlayerForCeremony(player)
                player.teleport(spawnLoc)

                GameManager.setCeremonyBounds(
                    uuid,
                    GameManager.CeremonyBounds(
                        worldName = ceremonyWorld.name,
                        minX = allowedMinX,
                        maxX = allowedMaxX,
                        minZ = allowedMinZ,
                        maxZ = allowedMaxZ,
                        safeLocation = spawnLoc
                    )
                )
            }
        }
        // Teleport spectators to a separate point (no movement limits).
        val specSpawn = SplatoonSettings.ceremonySpectatorSpawn?.let { sp ->
            Location(ceremonyWorld, sp.x, sp.y, sp.z, sp.yaw ?: 0f, sp.pitch ?: 0f)
        } ?: ceremonyWorld.spawnLocation.clone().add(0.5, 0.0, 0.5).apply {
            if (y < 75.0) y = 75.0
        }

        spectators.forEach { uuid ->
            val p = Bukkit.getPlayer(uuid) ?: return@forEach
            p.teleport(specSpawn)
            p.gameMode = GameMode.SPECTATOR
            p.isCollidable = false
            Bukkit.getScheduler().runTaskLater(SplatoonPlugin.instance, Runnable {
                if (spectators.contains(uuid)) {
                    p.gameMode = GameMode.SPECTATOR
                    p.isCollidable = false
                }
            }, 1L)
        }


        val durationTicks = (SplatoonSettings.ceremonyDurationSeconds.coerceAtLeast(0) * 20).toLong()
        ceremonyTask?.cancel(); ceremonyTask = null
        ceremonyTask = Bukkit.getScheduler().runTaskLater(SplatoonPlugin.instance, Runnable {
            endCeremonyAndFinalize()
        }, durationTicks)

        return true
    }

    private fun resetPlayerForCeremony(player: Player) {
        player.inventory.clear()
        player.activePotionEffects.forEach { eff -> player.removePotionEffect(eff.type) }
        player.foodLevel = 20
        player.saturation = 20f
        player.fireTicks = 0
        player.health = player.maxHealth
        player.gameMode = GameMode.ADVENTURE
        player.isGliding = false

        giveCeremonyFunItems(player)
    }

    private fun giveCeremonyFunItems(player: Player) {
        // В церемонии хотим дать "игрушечные" пушки/бомбы: стрелять можно бесконечно,
        // но попадания не должны красить и не должны наносить урон.
        // Эффекты отключаются в listeners по метке projectile'а.

        // Gun
        val gun = ItemStack(Material.CROSSBOW, 1)
        val gunMeta = gun.itemMeta
        gunMeta.displayName(Component.text("Сплат-пушка").color(TextColor.color(0xFF55FF)))
        gunMeta.persistentDataContainer.set(
            NamespacedKey(SplatoonPlugin.instance, "splatGun"),
            PersistentDataType.BOOLEAN,
            true
        )

        (gunMeta as? CrossbowMeta)?.let { crossbow ->
            if (crossbow.chargedProjectiles.isEmpty()) {
                runCatching { crossbow.addChargedProjectile(ItemStack(Material.ARROW, 1)) }
            }
        }
        gunMeta.setCustomModelData(GameManager.getSkin(player.uniqueId))
        gun.itemMeta = gunMeta

        // Bomb
        val bomb = ItemStack(Material.GOLDEN_AXE, 1)
        val bombMeta = bomb.itemMeta
        bombMeta.displayName(Component.text("Сплат-бомба").color(TextColor.color(0xFFAA00)))
        bombMeta.persistentDataContainer.set(
            NamespacedKey(SplatoonPlugin.instance, "Bomb"),
            PersistentDataType.BOOLEAN,
            true
        )
        bomb.itemMeta = bombMeta

        player.inventory.addItem(gun)
        player.inventory.addItem(bomb)
    }

    private fun endCeremonyAndFinalize() {
        val result = pendingMatchResult
        if (result != null) {
            recordMatchResultIfNeeded(result)
        }
        finishCleanupNow(force = false)
    }


    private fun determineWinner(): Int {
        var maxScore = -1
        var winningTeam = 0
        paintedCommand.forEach { (team, score) ->
            if (score > maxScore) {
                maxScore = score
                winningTeam = team
            }
        }
        return winningTeam
    }

    private fun startCountdown(worldName: String) {
        countdownLeft = 6

        countdownTask?.cancel()
        countdownTask = Bukkit.getScheduler().runTaskTimer(SplatoonPlugin.instance, Runnable {
            val countdown = countdownLeft ?: return@Runnable

            when (countdown) {
                6 -> {
                    val w = Bukkit.getWorld(this.worldName)
                    val targets = (w?.players ?: emptyList()).toMutableList()
                    commands.keys.forEach { id ->
                        val p = Bukkit.getPlayer(id)
                        if (p != null && !targets.contains(p)) targets.add(p)
                    }

                    targets.forEach { p ->
                        val colors = mapOf(
                            0 to Component.text("Ваша команда: Красные!", NamedTextColor.RED),
                            3 to Component.text("Ваша команда: Синие!", NamedTextColor.BLUE),
                            2 to Component.text("Ваша команда: Зелёные!", NamedTextColor.GREEN),
                            1 to Component.text("Ваша команда: Жёлтые!", NamedTextColor.YELLOW)
                        )
                        val subtitle = colors[commands[p.uniqueId]]
                            ?: Component.text("Матч начинается!", NamedTextColor.GRAY)

                        val titleColor = when (commands[p.uniqueId]) {
                            0 -> NamedTextColor.RED
                            1 -> NamedTextColor.YELLOW
                            2 -> NamedTextColor.GREEN
                            3 -> NamedTextColor.BLUE
                            else -> NamedTextColor.GOLD
                        }

                        val titleObj = Title.title(
                            Component.text("ПОДГОТОВКА!", titleColor),
                            subtitle,
                            Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1800), Duration.ofMillis(200))
                        )
                        p.showTitle(titleObj)
                    }

                    playSoundToAllPlayers(
                        Sound.sound(Key.key("block.note_block.pling"), Sound.Source.MASTER, 1.0f, 1.0f)
                    )
                }

                3 -> {
                    showTitleToWorldPlayers(
                        Component.text("3", NamedTextColor.YELLOW),
                        Component.empty()
                    )
                    playSoundToAllPlayers(
                        Sound.sound(Key.key("block.note_block.pling"), Sound.Source.MASTER, 1.0f, 1.0f)
                    )
                }

                2 -> {
                    showTitleToWorldPlayers(
                        Component.text("2", NamedTextColor.GOLD),
                        Component.empty()
                    )
                    playSoundToAllPlayers(
                        Sound.sound(Key.key("block.note_block.pling"), Sound.Source.MASTER, 1.0f, 1.2f)
                    )
                }

                1 -> {
                    showTitleToWorldPlayers(
                        Component.text("1", NamedTextColor.RED),
                        Component.empty()
                    )
                    playSoundToAllPlayers(
                        Sound.sound(Key.key("block.note_block.pling"), Sound.Source.MASTER, 1.0f, 1.4f)
                    )
                }

                0 -> {
                    val w = Bukkit.getWorld(this.worldName)
                    if (w != null) {
                        doCountdownStartNow(w)
                    } else {
                        countdownTask?.cancel()
                        countdownTask = null
                        countdownLeft = null
                    }
                    return@Runnable
                }
            }

            countdownLeft = countdown - 1
        }, 0L, 20L)
    }

    private fun doCountdownStartNow(world: World) {
        if (ended) return
        if (gameTimerTask != null) return

        showTitleToWorldPlayers(
            Component.text("СТАРТ!", NamedTextColor.GREEN),
            Component.text("Закрашивайте территорию!", NamedTextColor.GRAY)
        )
        playSoundToAllPlayers(
            Sound.sound(Key.key("entity.player.levelup"), Sound.Source.MASTER, 1.0f, 1.0f)
        )

        giveSplatGuns()
        sendStartInstructions()

        startMainTimer(world.name)
        startBoostTimer()
        startActionBarLoop()

        countdownTask?.cancel()
        countdownTask = null
        countdownLeft = null
    }

    private fun sendStartInstructions() {
        commands.keys.forEach { id ->
            val p = Bukkit.getPlayer(id) ?: return@forEach

            Lang.componentList(
                "messages.start_instructions",
                "max_hp" to maxInkHp.toString(),
                "bacillus_time" to SplatoonSettings.bacillusDurationSeconds.toString()
            ).forEach { p.sendMessage(it) }
        }
    }

    private fun startActionBarLoop() {
        actionBarTask?.cancel()
        actionBarTask = Bukkit.getScheduler().runTaskTimer(SplatoonPlugin.instance, Runnable {
            commands.keys.forEach { id ->
                val p = Bukkit.getPlayer(id) ?: return@forEach

                // Keep the HP bar strictly in sync with Ink HP and apply squid regen on own ink.
                syncHealthBar(p)
                keepFoodFull(p)
                tickInkRegen(p)

                updateSpawnProtectionMovement(p)
                val spawnSafe = isSpawnSafe(p)
                updateSpawnGlow(p, spawnSafe)
                p.sendActionBar(buildActionBar(p, spawnSafe))
            }
        }, 0L, SplatoonSettings.actionbarUpdateTicks)
    }

    private fun keepFoodFull(player: Player) {
        if (player.foodLevel != 20) player.foodLevel = 20
        if (player.saturation < 20f) player.saturation = 20f
        if (player.exhaustion != 0f) player.exhaustion = 0f
    }

    private fun tickInkRegen(player: Player) {
        if (!SplatoonSettings.inkRegenEnabled) return
        val uuid = player.uniqueId
        val cur = getInkHp(uuid)
        if (cur >= maxInkHp) {
            inkRegenCarry.remove(uuid)
            return
        }

        // Regen only while sneaking on own ink (squid mode).
        if (!player.isSneaking) {
            inkRegenCarry.remove(uuid)
            return
        }

        val invisEnabled = SplatoonSettings.sneakOnInkInvisibilityAmplifier >= 0
        val squidEffectOk = if (invisEnabled) {
            player.hasPotionEffect(PotionEffectType.INVISIBILITY)
        } else {
            player.hasPotionEffect(PotionEffectType.SPEED)
        }
        if (!squidEffectOk) {
            inkRegenCarry.remove(uuid)
            return
        }

        val now = System.currentTimeMillis()
        val lastDamage = inkLastDamageAt[uuid] ?: 0L
        val delayMs = SplatoonSettings.inkRegenDelayAfterDamageSeconds * 1000L
        if (delayMs > 0 && now - lastDamage < delayMs) return

        val dtSeconds = SplatoonSettings.actionbarUpdateTicks.toDouble() / 20.0
        val add = SplatoonSettings.inkRegenRatePerSecond * dtSeconds
        if (add <= 0.0) return

        var carry = (inkRegenCarry[uuid] ?: 0.0) + add
        var hp = cur
        while (carry >= 1.0 && hp < maxInkHp) {
            hp += 1
            carry -= 1.0
        }

        inkRegenCarry[uuid] = carry
        if (hp != cur) {
            inkHp[uuid] = hp
            syncHealthBar(player)
        }
    }


    private fun buildActionBar(player: Player, spawnSafe: Boolean): Component {
        var hasAny = false
        // IMPORTANT: keep this typed as Component.
        // In some Adventure/Paper versions Component.empty() is TextComponent,
        // and Kotlin may infer TextComponent here, causing type mismatches on later assignments.
        var c: Component = Component.empty()

        val base = commands[player.uniqueId]
        val ov = ammoOverride[player.uniqueId]
        if (base != null && ov != null && ov.first != base) {
            val now = System.currentTimeMillis()
            val leftMs = (ov.second - now).coerceAtLeast(0)
            val leftSec = ceil(leftMs / 1000.0).toInt()
            c = c.append(Component.text("☣ Bacillus ${leftSec}с", NamedTextColor.LIGHT_PURPLE))
            hasAny = true
        }

        if (spawnSafe) {
            if (hasAny) c = c.append(Component.text("  "))
            c = c.append(Component.text("SPAWN", NamedTextColor.GREEN))
            hasAny = true
        }

        val ammoTeam = getAmmoTeam(player.uniqueId)
        if (ammoTeam != null) {
            val ammoColor = when (ammoTeam) {
                0 -> NamedTextColor.RED
                1 -> NamedTextColor.YELLOW
                2 -> NamedTextColor.GREEN
                3 -> NamedTextColor.BLUE
                else -> NamedTextColor.WHITE
            }
            if (hasAny) c = c.append(Component.text("  |  ", NamedTextColor.GRAY))
            c = c.append(Component.text("Оружие: ", NamedTextColor.WHITE))
                 .append(Component.text("■■■■■", ammoColor))
            hasAny = true
        }

        val now = System.currentTimeMillis()
        val overlayUntil = actionBarOverlayUntilMs[player.uniqueId] ?: 0L
        val overlay = if (overlayUntil > now) actionBarOverlayText[player.uniqueId] else null
        if (overlayUntil <= now) {
            actionBarOverlayUntilMs.remove(player.uniqueId)
            actionBarOverlayText.remove(player.uniqueId)
        }

        if (overlay != null) {
            c = if (hasAny) overlay.append(Component.text("  ")).append(c) else overlay
            hasAny = true
        }

        return if (hasAny) c else Component.empty()
    }

    fun getInkHp(uuid: UUID): Int {
        return inkHp[uuid] ?: maxInkHp
    }

    fun resetInkHp(uuid: UUID) {
        inkHp[uuid] = maxInkHp
        syncHealthBar(uuid)
    }

    fun damageInkHp(uuid: UUID, amount: Int): Int {
        val cur = getInkHp(uuid)
        val next = (cur - amount).coerceIn(0, maxInkHp)
        inkHp[uuid] = next
        inkLastDamageAt[uuid] = System.currentTimeMillis()
        inkRegenCarry[uuid] = 0.0
        syncHealthBar(uuid)
        return next
    }

    private fun giveSplatGuns() {
        val item = ItemStack(Material.CROSSBOW, 1)
        val meta = item.itemMeta
        meta.displayName(Component.text("Сплат-пушка").color(TextColor.color(0xFF55FF)))
        meta.persistentDataContainer.set(
            NamespacedKey(SplatoonPlugin.instance, "splatGun"),
            PersistentDataType.BOOLEAN,
            true
        )

        // Держим арбалет визуально "заряженным" всегда, чтобы моделька не дёргалась.
        (meta as? CrossbowMeta)?.let { crossbow ->
            if (crossbow.chargedProjectiles.isEmpty()) {
                runCatching { crossbow.addChargedProjectile(ItemStack(Material.ARROW, 1)) }
            }
        }
        item.itemMeta = meta

        commands.keys.forEach { uuid ->
            val p = Bukkit.getPlayer(uuid) ?: return@forEach
            val playerItem = item.clone()
            val pMeta = playerItem.itemMeta
            pMeta.setCustomModelData(GameManager.getSkin(uuid))
            playerItem.itemMeta = pMeta
            p.inventory.addItem(playerItem)
        }
    }

    private fun playSoundToAllPlayers(soundKey: String, volume: Float, pitch: Float) {
        playSoundToAllPlayers(Sound.sound(Key.key(soundKey), Sound.Source.MASTER, volume, pitch))
    }

    private fun playSoundToAllPlayers(sound: Sound) {
        commands.keys.forEach { playerId ->
            Bukkit.getPlayer(playerId)?.playSound(sound)
        }
    }

    private fun showTitleToAllPlayers(title: Component, subtitle: Component) {
        val titleObj = Title.title(
            title,
            subtitle,
            Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2000), Duration.ofMillis(200))
        )
        commands.keys.forEach { playerId ->
            Bukkit.getPlayer(playerId)?.showTitle(titleObj)
        }
    }

    private fun showTitleToWorldPlayers(title: Component, subtitle: Component) {
        val w = Bukkit.getWorld(this.worldName)
        val targets = (w?.players ?: emptyList()).toMutableList()
        commands.keys.forEach { id ->
            val p = Bukkit.getPlayer(id)
            if (p != null && !targets.contains(p)) targets.add(p)
        }
        val titleObj = Title.title(
            title,
            subtitle,
            Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2000), Duration.ofMillis(200))
        )
        targets.forEach { it.showTitle(titleObj) }
    }

    private fun sendTitleToAllPlayers(title: String, subtitle: String, fadeIn: Int, stay: Int, fadeOut: Int) {
        val serializer = LegacyComponentSerializer.legacySection()
        val titleObj = Title.title(
            serializer.deserialize(title),
            serializer.deserialize(subtitle),
            Title.Times.times(
                Duration.ofMillis(fadeIn.toLong() * 50L),
                Duration.ofMillis(stay.toLong() * 50L),
                Duration.ofMillis(fadeOut.toLong() * 50L)
            )
        )

        val w = Bukkit.getWorld(this.worldName)
        val targets = (w?.players ?: emptyList()).toMutableList()
        commands.keys.forEach { id ->
            val p = Bukkit.getPlayer(id)
            if (p != null && !targets.contains(p)) targets.add(p)
        }
        targets.forEach { it.showTitle(titleObj) }
    }

    private fun showWinnerAnnouncement(winner: Int, ceremonyStarted: Boolean) {
        val teamName = teamLabel(winner)

        val subtitle = if (ceremonyStarted) {
            "§7Порадуемся за победителей!"
        } else {
            "§7Возвращение в лобби через §f${SplatoonSettings.returnToLobbyDelaySeconds}§7 сек..."
        }

        sendTitleToAllPlayers(
            title = "§6Победа: $teamName",
            subtitle = subtitle,
            fadeIn = 10,
            stay = 60,
            fadeOut = 20
        )
        playSoundToAllPlayers("minecraft:entity.player.levelup", 1f, 1f)
    }


    private fun startBoostTimer() {
        if (!SplatoonSettings.boostsEnabled || SplatoonSettings.boostChances.isEmpty()) return
        scheduleNextBoost(0L)
    }

    private fun scheduleNextBoost(delayTicks: Long) {
        boostTimerTask?.cancel()

        boostTimerTask = Bukkit.getScheduler().runTaskLater(SplatoonPlugin.instance, Runnable {
            val w = Bukkit.getWorld(worldName) ?: return@Runnable

            if (SplatoonSettings.boostLocations.size != layingBoosts.size) {
                val buffer = SplatoonSettings.boostLocations.toMutableList()

                buffer.removeAll(layingBoosts.keys)

                val rLoc = buffer[Random.nextInt(buffer.size)]

                val loc = Location(w, rLoc[0], rLoc[1], rLoc[2])

                var rName = ""

                val rIndex = Random.nextInt(SplatoonSettings.boostChancesSum)

                for (name in SplatoonSettings.boostChances.keys) {
                    val range = SplatoonSettings.boostChances[name]!!

                    if ((range[0] <= rIndex) && (rIndex <= range[1])) {
                        rName = name
                        break
                    }
                }

                val display = makeBoost(rName, w, loc)

                if (display != null) {
                    layingBoosts.put(rLoc, display)
                }
            }

            val minSec = SplatoonSettings.boostsMinIntervalSeconds
            val maxSec = SplatoonSettings.boostsMaxIntervalSeconds
            val nextSec = if (maxSec <= minSec) minSec else Random.nextInt(minSec, maxSec + 1)

            scheduleNextBoost(nextSec * 20L)
        }, delayTicks)
    }

    private fun makeBoost(name: String, w: World, loc: Location): ItemDisplay? {
        val display: ItemDisplay = w.spawn(loc, ItemDisplay::class.java) { display ->
            display.isGlowing = true
        }

        when (name) {
            "bomb" -> {
                display.setItemStack(ItemStack(Material.GOLDEN_AXE, 1))
                display.addScoreboardTag("bomb")
            }
            "bacillus" -> {
                display.setItemStack(ItemStack(Material.AMETHYST_SHARD, 1))
                display.addScoreboardTag("bacillus")
            }
            else -> {
                display.remove()
                return null
            }
        }

        display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
        display.isGlowing = true

        return display
    }

    private fun startMainTimer(worldName: String) {
        timeLeft = totalTime

        val w = Bukkit.getWorld(worldName)
        if (w != null) recalcPaintableBlocks(w)

        clearScoreboards()
        createPlayerScoreboards()

        removeBossBar()
        createBossBar()

        gameTimerTask = Bukkit.getScheduler().runTaskTimer(SplatoonPlugin.instance, Runnable {
            updateBossBar()

            if (timeLeft <= 0) {
                endGame(worldName)
                return@Runnable
            }

            when (timeLeft) {
                60 -> {
                    showTitleToAllPlayers(
                        Component.text("Осталась 1 минута!", NamedTextColor.YELLOW),
                        Component.empty()
                    )
                    playSoundToAllPlayers(
                        Sound.sound(Key.key("block.note_block.bell"), Sound.Source.MASTER, 1.0f, 1.0f)
                    )
                }

                30 -> {
                    showTitleToAllPlayers(
                        Component.text("30 секунд!", NamedTextColor.GOLD),
                        Component.empty()
                    )
                    playSoundToAllPlayers(
                        Sound.sound(Key.key("block.note_block.bell"), Sound.Source.MASTER, 1.0f, 1.2f)
                    )
                }

                3, 2, 1 -> {
                    showTitleToAllPlayers(
                        Component.text("$timeLeft", NamedTextColor.RED),
                        Component.empty()
                    )
                    playSoundToAllPlayers(
                        Sound.sound(
                            Key.key("block.note_block.pling"),
                            Sound.Source.MASTER,
                            1.0f,
                            (1.0f + (10 - timeLeft) * 0.1f)
                        )
                    )
                }
            }

            timeLeft--
        }, 0L, 20L)

        scoreboardUpdateTask = Bukkit.getScheduler().runTaskTimer(SplatoonPlugin.instance, Runnable {
            updateAllPlayerScoreboards()
        }, 0L, SplatoonSettings.scoreboardUpdateTicks)
    }

    fun recalcPaintableBlocks(world: World) {
        totalPaintableBlocks = 0
        paintedCommand.keys.forEach { paintedCommand[it] = 0 }

        val materialToTeam = mutableMapOf<Material, Int>()
        commandColors.forEach { (team, mat) -> materialToTeam[mat] = team }

        val paintable = setOf(
            Material.WHITE_CONCRETE,
            Material.RED_CONCRETE,
            Material.YELLOW_CONCRETE,
            Material.GREEN_CONCRETE,
            Material.BLUE_CONCRETE
        )

        world.loadedChunks.forEach { chunk ->
            for (x in 0..15) {
                for (z in 0..15) {
                    for (y in world.minHeight until world.maxHeight) {
                        val b = chunk.getBlock(x, y, z)
                        if (!paintable.contains(b.type)) continue
                        totalPaintableBlocks++
                        val team = materialToTeam[b.type]
                        if (team != null) paintedCommand[team] = (paintedCommand[team] ?: 0) + 1
                    }
                }
            }
        }
    }

    fun applyAmmoOverride(uuid: UUID, team: Int, durationMs: Long) {
        ammoOverride[uuid] = team to (System.currentTimeMillis() + durationMs)
    }

    fun getAmmoTeam(uuid: UUID): Int? {
        val base = commands[uuid] ?: return null
        val override = ammoOverride[uuid]
        if (override != null) {
            if (System.currentTimeMillis() < override.second) return override.first
            ammoOverride.remove(uuid)
        }
        return base
    }


    fun setSpawnProtection(player: Player, durationMs: Long) {
        val uuid = player.uniqueId
        if (durationMs <= 0) {
            clearSpawnProtection(uuid, player)
            updateSpawnGlow(player, false)
            return
        }
        spawnProtectedUntil[uuid] = System.currentTimeMillis() + durationMs
        spawnProtectedOrigin[uuid] = player.location.toVector()
        spawnProtectionMoved[uuid] = false

        // Защищаем игрока от толканий/коллизий другими игроками, пока активен spawn protection.
        player.isCollidable = false

        updateSpawnGlow(player, true)
    }

    private fun clearSpawnProtection(uuid: UUID, player: Player? = null) {
        spawnProtectedUntil.remove(uuid)
        spawnProtectedOrigin.remove(uuid)
        spawnProtectionMoved.remove(uuid)
        if (player != null) {
            player.isCollidable = true
        }
    }

    fun isSpawnSafe(player: Player): Boolean {
        val uuid = player.uniqueId
        val until = spawnProtectedUntil[uuid] ?: return false

        val now = System.currentTimeMillis()
        if (now < until) return true

        val moved = spawnProtectionMoved[uuid] ?: return false
        if (moved) return false

        val origin = spawnProtectedOrigin[uuid] ?: return false
        val cur = player.location
        if (hasMoved(cur, origin)) {
            spawnProtectionMoved[uuid] = true
            clearSpawnProtection(uuid, player)
            return false
        }
        return true
    }

    fun onPlayerMoved(player: Player) {
        val uuid = player.uniqueId
        val until = spawnProtectedUntil[uuid] ?: return
        val origin = spawnProtectedOrigin[uuid] ?: return

        val moved = spawnProtectionMoved[uuid] ?: return
        if (moved) return

        val cur = player.location
        if (hasMoved(cur, origin)) {
            spawnProtectionMoved[uuid] = true
            if (System.currentTimeMillis() >= until) {
                clearSpawnProtection(uuid, player)
            }
        }
    }

    private fun updateSpawnProtectionMovement(player: Player) {
        val uuid = player.uniqueId
        val until = spawnProtectedUntil[uuid] ?: return
        val origin = spawnProtectedOrigin[uuid] ?: return
        val moved = spawnProtectionMoved[uuid] ?: return

        val now = System.currentTimeMillis()

        if (!moved) {
            val cur = player.location
            if (hasMoved(cur, origin)) {
                spawnProtectionMoved[uuid] = true
                if (now >= until) {
                    clearSpawnProtection(uuid, player)
                }
            }
            return
        }

        if (now >= until) {
            clearSpawnProtection(uuid, player)
        }
    }

    private fun hasMoved(cur: Location, origin: Vector): Boolean {
        // Не считаем мелкие сдвиги (толкания/погрешности позиции) как "движение со спавна".
        // Сбрасываем спавн-протекшн только когда игрок реально вышел из исходного блока.
        if (cur.blockX != origin.blockX || cur.blockZ != origin.blockZ) return true
        return abs(cur.blockY - origin.blockY) >= 1
    }

    fun teleportToSpawn(player: Player) {
        val w = Bukkit.getWorld(worldName) ?: return

        val loc = pickSpawnLocation(w, player.uniqueId)

        player.teleportAsync(loc)
    }

    private fun pickSpawnLocation(world: World, uuid: UUID): org.bukkit.Location {
        if (spawns.isEmpty()) return world.spawnLocation

        val bufferSpawns = spawns.toMutableList()

        bufferSpawns.remove(globalPreviousSpawn)

        bufferSpawns.remove(playerPreviousSpawns[uuid])

        if (bufferSpawns.isEmpty()) {
            playerPreviousSpawns.remove(uuid)
            globalPreviousSpawn = null

            return world.spawnLocation
        }

        val chosen = bufferSpawns[Random.nextInt(bufferSpawns.size)]

        playerPreviousSpawns[uuid] = chosen
        globalPreviousSpawn = chosen

        val fallback = world.spawnLocation
        val yaw = chosen.yaw ?: fallback.yaw
        val pitch = chosen.pitch ?: fallback.pitch
        return Location(world, chosen.x, chosen.y, chosen.z, yaw, pitch)
    }

    private fun createBossBar() {
        removeBossBar()

        val color = bossBarColor()
        val placementByTeam = computePlacementByTeam()

        activeTeams.forEach { team ->
            val bar = Bukkit.createBossBar(formatBossBarTitle(team, placementByTeam), color, BarStyle.SOLID)
            bar.isVisible = true

            commands.entries
                .asSequence()
                .filter { it.value == team }
                .map { it.key }
                .forEach { uuid ->
                    Bukkit.getPlayer(uuid)?.let { bar.addPlayer(it) }
                }

            bossBarsByTeam[team] = bar
        }

        val specBar = Bukkit.createBossBar(formatBossBarTitle(null, placementByTeam), color, BarStyle.SOLID)
        specBar.isVisible = true
        spectators.forEach { uuid ->
            Bukkit.getPlayer(uuid)?.let { specBar.addPlayer(it) }
        }
        spectatorBossBar = specBar

        updateBossBar()
    }

    fun addSpectator(player: Player) {
        val uuid = player.uniqueId
        if (spectators.contains(uuid)) {
            // Ensure spectator mode is applied (world gamemode may override on teleport).
            player.gameMode = GameMode.SPECTATOR
            player.isCollidable = false
            ensureBossBarsCreated()
            removeFromAllBossBars(player)
            spectatorBossBar?.addPlayer(player)

            Bukkit.getScheduler().runTaskLater(SplatoonPlugin.instance, Runnable {
                if (spectators.contains(uuid)) {
                    player.gameMode = GameMode.SPECTATOR
                    player.isCollidable = false
                }
            }, 1L)

            return
        }

        val world = Bukkit.getWorld(worldName)
        if (world == null) {
            player.sendMessage("§cМир игры не найден: $worldName")
            return
        }

        spectatorBackups[uuid] = SpectatorBackup(
            location = player.location.clone(),
            gameMode = player.gameMode,
            allowFlight = player.allowFlight,
            isFlying = player.isFlying,
            maxHealthBase = player.getAttribute(Attribute.MAX_HEALTH)?.baseValue ?: 20.0,
            inventoryContents = player.inventory.contents.map { it?.clone() }.toTypedArray(),
            armorContents = player.inventory.armorContents.map { it?.clone() }.toTypedArray(),
            extraContents = player.inventory.extraContents.map { it?.clone() }.toTypedArray(),
            level = player.level,
            exp = player.exp,
            health = player.health,
            foodLevel = player.foodLevel,
            saturation = player.saturation,
            absorption = player.absorptionAmount,
            potionEffects = player.activePotionEffects.map { pe ->
                PotionEffect(pe.type, pe.duration, pe.amplifier, pe.isAmbient, pe.hasParticles(), pe.hasIcon())
            },
            scoreboard = player.scoreboard
        )

        // Remove from queue/lobby UI if present.
        runCatching { MatchmakingManager.removePlayer(player) }

        player.inventory.clear()
        player.activePotionEffects.forEach { effect ->
            player.removePotionEffect(effect.type)
        }
        restoreVanillaHealth(player)
        player.foodLevel = 20
        player.saturation = 20f
        player.absorptionAmount = 0.0
        val tp = world.spawnLocation.clone().add(0.5, 0.0, 0.5)
        if (tp.y < 75.0) tp.y = 75.0
        player.teleport(tp)

        spectators.add(uuid)
        GameManager.setSpectating(uuid, worldName)

        ensureBossBarsCreated()
        removeFromAllBossBars(player)
        spectatorBossBar?.addPlayer(player)

        // Multiverse can enforce world gamemode on teleport; apply spectator mode after teleport (and once more next tick).
        player.gameMode = GameMode.SPECTATOR
        player.isCollidable = false
        Bukkit.getScheduler().runTaskLater(SplatoonPlugin.instance, Runnable {
            if (spectators.contains(uuid)) {
                player.gameMode = GameMode.SPECTATOR
                player.isCollidable = false
            }
        }, 1L)

        // Give the same scoreboard UI as players (without "Вы"/"ВКЛАД" details).
        val sb = Bukkit.getScoreboardManager().newScoreboard

        sb.getObjective("gametimer")?.unregister()

        val obj = sb.registerNewObjective(
            "gametimer",
            Criteria.DUMMY,
            Lang.component("scoreboard.title")
        )
        obj.displaySlot = DisplaySlot.SIDEBAR
        obj.numberFormat(NumberFormat.blank())

        player.scoreboard = sb
        playerScoreboards[uuid] = sb
        playerObjectives[uuid] = obj

        updateAllPlayerScoreboards()

        player.sendMessage("§aРежим наблюдения: §f$arenaId §7($worldName)")
    }

    fun removeSpectator(player: Player, silent: Boolean = false, forceLobby: Boolean = false) {
        val uuid = player.uniqueId
        if (!spectators.remove(uuid)) return
        removeFromAllBossBars(player)
        GameManager.clearSpectating(uuid)

        playerObjectives.remove(uuid)
        playerScoreboards.remove(uuid)

        val backup = spectatorBackups.remove(uuid)
        if (backup != null) {
            player.inventory.clear()
            player.inventory.contents = backup.inventoryContents.map { it?.clone() }.toTypedArray()
            player.inventory.armorContents = backup.armorContents.map { it?.clone() }.toTypedArray()
            player.inventory.extraContents = backup.extraContents.map { it?.clone() }.toTypedArray()

            player.activePotionEffects.forEach { effect ->
                player.removePotionEffect(effect.type)
            }
            backup.potionEffects.forEach { pe ->
                runCatching { player.addPotionEffect(pe) }
            }

            player.gameMode = backup.gameMode
            player.allowFlight = backup.allowFlight
            player.isFlying = backup.isFlying
            player.isCollidable = true

            player.getAttribute(Attribute.MAX_HEALTH)?.baseValue = backup.maxHealthBase
            player.absorptionAmount = backup.absorption
            player.health = backup.health.coerceAtMost((player.getAttribute(Attribute.MAX_HEALTH)?.baseValue ?: 20.0))
            player.foodLevel = backup.foodLevel
            player.saturation = backup.saturation
            player.level = backup.level
            player.exp = backup.exp

            player.scoreboard = backup.scoreboard

            if (forceLobby) {
                val lobbyWorld = Bukkit.getWorld(SplatoonSettings.lobbyWorldName)
                val spawn = lobbyWorld?.spawnLocation ?: Bukkit.getWorlds()[0].spawnLocation
                player.teleport(spawn)
            } else {
                player.teleport(backup.location)
            }
        } else {
            player.isCollidable = true
            if (forceLobby) {
                GameManager.sendToLobby(player)
            }
        }

        if (!silent) {
            player.sendMessage("§eТы вышел из режима наблюдения.")
        }
    }

    fun forceRemoveAllSpectators(forceLobby: Boolean) {
        val list = spectators.toList()
        list.forEach { uuid ->
            val p = Bukkit.getPlayer(uuid)
            if (p != null) {
                runCatching { removeSpectator(p, silent = true, forceLobby = forceLobby) }
            } else {
                spectators.remove(uuid)
                spectatorBackups.remove(uuid)
                GameManager.clearSpectating(uuid)
            }
        }
    }
    private fun removeBossBar() {
        bossBarsByTeam.values.forEach { it.removeAll() }
        bossBarsByTeam.clear()
        spectatorBossBar?.removeAll()
        spectatorBossBar = null
    }
    private fun bossBarColor(): BarColor {
        return when {
            timeLeft <= 30 -> BarColor.RED
            timeLeft <= 60 -> BarColor.YELLOW
            else -> BarColor.GREEN
        }
    }

    private fun computePlacementByTeam(): Map<Int, Int> {
        if (activeTeams.isEmpty()) return emptyMap()

        val scoreByTeam = mutableMapOf<Int, Double>()
        activeTeams.forEach { t ->
            val blocks = paintedCommand[t] ?: 0
            val score = if (totalPaintableBlocks > 0) blocks * 100.0 / totalPaintableBlocks else blocks.toDouble()
            scoreByTeam[t] = score
        }

        val sorted = activeTeams
            .sortedWith(compareByDescending<Int> { scoreByTeam[it] ?: 0.0 }.thenBy { it })

        val placementByTeam = mutableMapOf<Int, Int>()
        var place = 1
        sorted.forEach { t ->
            placementByTeam[t] = place
            place++
        }
        return placementByTeam
    }

    private fun teamColorCode(team: Int): String {
        return when (team) {
            0 -> "\u00A7c"
            1 -> "\u00A7e"
            2 -> "\u00A7a"
            3 -> "\u00A79"
            else -> "\u00A7f"
        }
    }

    private fun formatBossBarTitle(team: Int?, placementByTeam: Map<Int, Int>): String {
        val minutes = timeLeft / 60
        val seconds = timeLeft % 60
        val timeString = "$minutes:${"%02d".format(seconds)}"
        val prefix = when {
            timeLeft <= 30 -> "\u00A7c"
            timeLeft <= 60 -> "\u00A7e"
            else -> "\u00A7a"
        }

        if (team == null || activeTeams.isEmpty()) {
            return "${prefix}До конца: \u00A7f$timeString"
        }

        val totalTeams = activeTeams.size
        val place = placementByTeam[team]
        val placeText = if (place != null) "${teamColorCode(team)}$place\u00A77/\u00A7f$totalTeams" else "\u00A77-"

        return "${prefix}До конца: \u00A7f$timeString \u00A78| \u00A7fМесто: $placeText"
    }

    private fun ensureBossBarsCreated() {
        if (bossBarsByTeam.isNotEmpty() || spectatorBossBar != null) return
        createBossBar()
    }

    private fun removeFromAllBossBars(player: Player) {
        bossBarsByTeam.values.forEach { it.removePlayer(player) }
        spectatorBossBar?.removePlayer(player)
    }

    private fun updateBossBar() {
        val progress = (timeLeft.toDouble() / totalTime.toDouble()).coerceIn(0.0, 1.0)
        val color = bossBarColor()
        val placementByTeam = computePlacementByTeam()

        bossBarsByTeam.forEach { (team, bar) ->
            bar.progress = progress
            bar.color = color
            bar.setTitle(formatBossBarTitle(team, placementByTeam))
        }

        spectatorBossBar?.let { bar ->
            bar.progress = progress
            bar.color = color
            bar.setTitle(formatBossBarTitle(null, placementByTeam))
        }
    }

    private fun clearScoreboards() {
        playerObjectives.clear()
        playerScoreboards.clear()
    }

    private fun createPlayerScoreboards() {
        commands.keys.forEach { uuid ->
            val player = Bukkit.getPlayer(uuid) ?: return@forEach

            val sb = Bukkit.getScoreboardManager().newScoreboard

            sb.getObjective("gametimer")?.unregister()

            val obj = sb.registerNewObjective(
                "gametimer",
                Criteria.DUMMY,
                Component.text("Splatoon", NamedTextColor.GOLD)
            )

            obj.displaySlot = DisplaySlot.SIDEBAR


            obj.numberFormat(NumberFormat.blank())

            player.scoreboard = sb

            playerScoreboards[uuid] = sb
            playerObjectives[uuid] = obj
        }

        updateAllPlayerScoreboards()
    }

    private fun updateSpawnNameTags() {
        val protectedPlayersByTeam = mutableMapOf<Int, MutableSet<String>>()
        for (i in 0..3) protectedPlayersByTeam[i] = mutableSetOf()

        commands.keys.forEach { uuid ->
            val p = Bukkit.getPlayer(uuid) ?: return@forEach
            if (isSpawnSafe(p)) {
                val teamId = commands[uuid] ?: return@forEach
                protectedPlayersByTeam[teamId]?.add(p.name)
            }
        }

        playerScoreboards.keys.forEach { viewerUuid ->
            val sb = playerScoreboards[viewerUuid] ?: return@forEach
            
            for (teamId in 0..3) {
                val teamName = "sp_spawn_$teamId"
                val sbTeam = sb.getTeam(teamName) ?: sb.registerNewTeam(teamName).apply {
                    val teamColor = when (teamId) {
                        0 -> NamedTextColor.RED
                        1 -> NamedTextColor.YELLOW
                        2 -> NamedTextColor.GREEN
                        3 -> NamedTextColor.BLUE
                        else -> NamedTextColor.WHITE
                    }
                    color(teamColor)
                    setPrefix("§aSPAWN §r")
                    setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS)
                }

                val protectedNames = protectedPlayersByTeam[teamId] ?: emptySet()
                
                val toRemove = sbTeam.entries.filter { it !in protectedNames }
                toRemove.forEach { sbTeam.removeEntry(it) }

                protectedNames.forEach { name ->
                    if (!sbTeam.hasEntry(name)) sbTeam.addEntry(name)
                }
            }
            
            sb.getTeam("sp_spawn")?.unregister()
        }
    }
    private fun unique(text: String, index: Int): String {
        return text + "§r".repeat(index)
    }


    private fun setScoreboardLine(sb: Scoreboard, obj: Objective, score: Int, text: String) {
        val entryName = "§" + "0123456789abcdef"[score % 16] + "§" + "0123456789abcdef"[(score / 16) % 16] + "§r"
        var team = sb.getTeam("line_$score")
        if (team == null) {
            team = sb.registerNewTeam("line_$score")
            team.addEntry(entryName)
            obj.getScore(entryName).score = score
        }
        val comp = LegacyComponentSerializer.legacySection().deserialize(text)
        team.prefix(comp)
    }

    private fun removeScoreboardLine(sb: Scoreboard, obj: Objective, score: Int) {
        val entryName = "§" + "0123456789abcdef"[score % 16] + "§" + "0123456789abcdef"[(score / 16) % 16] + "§r"
        val team = sb.getTeam("line_$score")
        if (team != null) {
            sb.resetScores(entryName)
            team.unregister()
        }
    }

    private fun updateAllPlayerScoreboards() {
        updateSpawnNameTags()

        val totalForPercent = if (totalPaintableBlocks > 0) {
            totalPaintableBlocks
        } else {
            activeTeams.sumOf { paintedCommand[it] ?: 0 }
        }

        val teamTotals = mutableMapOf<Int, Int>()
        val teamSortedPlayers = mutableMapOf<Int, List<Pair<UUID, Int>>>()

        activeTeams.forEach { team ->
            val teamPlayers = commands.entries.filter { it.value == team }.map { it.key }
            teamTotals[team] = teamPlayers.sumOf { (paintedPerson[it] ?: 0).coerceAtLeast(0) }
            teamSortedPlayers[team] = teamPlayers
                .map { it to (paintedPerson[it] ?: 0) }
                .sortedByDescending { it.second }
        }

        val viewers = playerObjectives.keys.toList()
        viewers.forEach { uuid ->
            val sb = playerScoreboards[uuid] ?: return@forEach
            val obj = playerObjectives[uuid] ?: return@forEach

            val viewerTeam = commands[uuid]

            var score = 15
            setScoreboardLine(sb, obj, score, unique(Lang.get("scoreboard.lines.separator"), score))
            score--

            setScoreboardLine(sb, obj, score, Lang.get("scoreboard.lines.score_title"))
            score--

            activeTeams.forEach { team ->
                if (score <= 0) return@forEach
                setScoreboardLine(sb, obj, score, formatTeamLine(team, totalForPercent, viewerTeam))
                score--
            }

            if (score <= 0) return@forEach
            setScoreboardLine(sb, obj, score, " ")
            score--
            
            val team = viewerTeam
            if (score <= 0) return@forEach
            setScoreboardLine(sb, obj, score, Lang.get("scoreboard.lines.you", "team" to teamLabel(team)))
            score--

            if (score <= 0) return@forEach
            setScoreboardLine(sb, obj, score, Lang.get("scoreboard.lines.ammo", "team" to teamLabel(getAmmoTeam(uuid))))
            score--

            if (score <= 0) return@forEach
            setScoreboardLine(sb, obj, score, "  ")
            score--

            if (score <= 0) return@forEach
            setScoreboardLine(sb, obj, score, "§f§lВКЛАД:")
            score--

            if (team != null) {
                val teamTotal = teamTotals[team] ?: 0
                val sorted = teamSortedPlayers[team] ?: emptyList()

                sorted.forEach { (pid, value) ->
                    if (score <= 0) return@forEach
                    setScoreboardLine(sb, obj, score, formatPlayerContributionLine(pid, value, teamTotal))
                    score--
                }
            }

            if (score > 0) {
                setScoreboardLine(sb, obj, score, unique(Lang.get("scoreboard.lines.separator"), 0))
                score--
            }

            while (score > 0) {
                removeScoreboardLine(sb, obj, score)
                score--
            }
        }
    }

    private fun teamLabel(team: Int?): String {
        return when (team) {
            0 -> "§cКрасные"
            1 -> "§eЖёлтые"
            2 -> "§aЗелёные"
            3 -> "§9Синие"
            else -> "§7-"
        }
    }

    private fun formatAmmoLine(uuid: UUID): String {
        val ammoTeam = getAmmoTeam(uuid)
        val baseTeam = commands[uuid]
        val prefix = if (ammoTeam != null && baseTeam != null && ammoTeam != baseTeam) "§d" else "§f"
        return "${prefix}Патроны: §f${teamLabel(ammoTeam)}"
    }
    private fun formatTeamLine(team: Int, total: Int, viewerTeam: Int?): String {
        val value = paintedCommand[team] ?: 0
        val percent = if (total <= 0) 0 else ((value * 100.0) / total).roundToInt()

        val marker = if (viewerTeam == team) "§6▶ " else ""

        val teamKey = when (team) {
            0 -> "red"
            1 -> "yellow"
            2 -> "green"
            3 -> "blue"
            else -> "red"
        }

        return Lang.get(
            "scoreboard.team_line",
            "marker" to marker,
            "team" to Lang.get("scoreboard.team.$teamKey"),
            "value" to value.toString(),
            "percent" to percent.toString(),
            "color" to ""
        )
    }

    private fun formatPlayerContributionLine(uuid: UUID, value: Int, teamTotal: Int): String {
        val name = Bukkit.getOfflinePlayer(uuid).name ?: "Player"
        val percent = if (teamTotal <= 0) 0 else ((value * 100.0) / teamTotal).roundToInt()
        val k = kills[uuid] ?: 0

        return Lang.get(
            "scoreboard.player_line",
            "player" to name.take(10),
            "value" to value.toString(),
            "percent" to percent.toString(),
            "kills" to k.toString()
        )
    }

    private fun ensureInkHealth(player: Player) {
        val attr = player.getAttribute(Attribute.MAX_HEALTH) ?: return
        val desired = (maxInkHp * 2).toDouble().coerceAtLeast(2.0)
        if (attr.baseValue != desired) {
            attr.baseValue = desired
        }
    }

    fun syncHealthBar(uuid: UUID) {
        val player = Bukkit.getPlayer(uuid) ?: return
        syncHealthBar(player)
    }

    fun syncHealthBar(player: Player) {
        ensureInkHealth(player)
        val maxHealth = player.getAttribute(Attribute.MAX_HEALTH)?.baseValue ?: 20.0
        val hp = getInkHp(player.uniqueId)
        val desiredHealth = (hp * 2).toDouble().coerceIn(1.0, maxHealth)
        if (player.absorptionAmount != 0.0) {
            player.absorptionAmount = 0.0
        }
        if (player.health != desiredHealth) {
            player.health = desiredHealth
        }
    }

    private fun restoreVanillaHealth(player: Player) {
        player.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 20.0
        player.health = 20.0
        player.absorptionAmount = 0.0
        player.isCollidable = true
        player.removePotionEffect(PotionEffectType.GLOWING)
    }

    private fun updateSpawnGlow(player: Player, enabled: Boolean) {
        if (!enabled) {
            player.removePotionEffect(PotionEffectType.GLOWING)
            return
        }
        player.addPotionEffect(PotionEffect(PotionEffectType.GLOWING, 40, 0, false, false, false))
    }
}
