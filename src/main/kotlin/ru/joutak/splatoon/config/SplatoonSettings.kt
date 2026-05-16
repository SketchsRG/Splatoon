package ru.joutak.splatoon.config

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.joutak.splatoon.SplatoonPlugin
import java.lang.reflect.AccessFlag
import java.util.logging.Logger
import kotlin.math.ceil
import kotlin.math.max

data class SpawnPoint(
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float? = null,
    val pitch: Float? = null
)

data class ArenaSettings(
    val id: String,
    val templateWorld: String,
    val teamCount: Int,
    val playersPerTeam: Int,
    val spawns: List<SpawnPoint>,
    /** How many parallel queue instances should be created for this arena template. */
    val instances: Int
)

data class CeremonyPodium(
    val place: Int,
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val maxX: Int,
    val maxY: Int,
    val maxZ: Int,
    val spawn: SpawnPoint? = null
)

object SplatoonSettings {
    var lobbyWorldName: String = "world"
        private set

    var defaultTemplateWorld: String = "sp_arena"
        private set

    /**
     * Global fallback for arena parallelism.
     * If arena config contains `instances: N`, it overrides this value.
     */
    var maxParallelGamesPerArena: Int = 1
        private set

    var gameDurationSeconds: Int = 300
        private set

    var returnToLobbyDelaySeconds: Int = 5
        private set


    var ceremonyEnabled: Boolean = false
        private set

    var ceremonyTemplateWorld: String = "sp_ceremony"
        private set

    // How long players stay in the ceremony room before returning (or being kicked by tournament).
    var ceremonyDurationSeconds: Int = 8
        private set

    // How many wind charges to give each player during ceremony (0 = none)
    var ceremonyWindCharges: Int = 0
        private set

    // Where to teleport spectators during ceremony (they are NOT bounded to podium squares)
    var ceremonySpectatorSpawn: SpawnPoint? = null
        private set

    // 4 podium squares (2x2 blocks each by default). Keys: place 1..4.
    val ceremonyPodiumsByPlace: MutableMap<Int, CeremonyPodium> = mutableMapOf()

    var scoreboardUpdateTicks: Long = 10
        private set

    var actionbarUpdateTicks: Long = 5
        private set

    var inkMaxHp: Int = 3
        private set

    var inkRegenEnabled: Boolean = true
        private set

    // While player is in "squid" mode (INVISIBILITY effect)
    // 1.0 = +1 Ink HP per second
    var inkRegenRatePerSecond: Double = 0.5
        private set

    var inkRegenDelayAfterDamageSeconds: Int = 2
        private set

    var spawnProtectionAfterRespawnSeconds: Int = 4
        private set

    var spawnProtectionResistanceDurationTicks: Int = 60
        private set

    var spawnProtectionResistanceAmplifier: Int = 10
        private set

    var spawnProtectionNoDamageTicks: Int = 60
        private set

    var gunVelocity: Double = 1.4
        private set

    var gunDisableGravity: Boolean = true
        private set

    var bombVelocity: Double = 1.1
        private set

    var bombHorizontalMultiplier: Double = 0.85
        private set

    var bombUpwardBoost: Double = 0.55
        private set

    var gunPaintRadius: Double = 1.5
        private set

    var gunPaintInLobbyRadius: Double = 1.0
        private set

    var bombPaintRadius: Double = 5.0
        private set

    var gunKillPaintRadius: Double = 3.0
        private set

    var bombKillPaintRadius: Double = 5.0
        private set

    var boostsEnabled: Boolean = true
        private set

    var boostsMinIntervalSeconds: Int = 18
        private set

    var boostsMaxIntervalSeconds: Int = 39
        private set

    var boostsBombPercent: Int = 70
        private set

    var bacillusDurationSeconds: Int = 5
        private set

    var bacillusRangeBlocks: Double = 3.2
        private set

    var bacillusCooldownMs: Long = 250
        private set

    var sneakOnInkEnabled: Boolean = true
        private set

    var sneakOnInkScanSteps: Int = 3
        private set

    var sneakOnInkScanStepBlocks: Double = 0.1
        private set

    var sneakOnInkSpeedAmplifier: Int = 18
        private set

    var sneakOnInkInvisibilityAmplifier: Int = 1
        private set

    var sneakOnInkRegenerationAmplifier: Int = 1
        private set

    var sneakOnInkEffectDurationTicks: Int = 2
        private set

    var sneakOnInkTaskPeriodTicks: Long = 1
        private set

    var boostChancesSum: Int = 0
        private set

    var speedupOnIceEnabled: Boolean = true
        private set

    var speedupOnIceAmplifier: Int = 2
        private set

    var jumpPadJumpAmplifier: Int = 5
        private set

    var jumpPadEffectDuration: Int = 200
        private set

    var jumpPadBlockType: String = "LIME_CONCRETE_POWDER"
        private set

    var defaultSkin: Int = 1000
        private set

    var skinsInventory: Inventory? = null
        private set

    var skinChanger: Material = Material.EMERALD
        private set

    val paintableMaterials: Set<Material> = setOf(
        Material.WHITE_CONCRETE,
        Material.RED_CONCRETE,
        Material.YELLOW_CONCRETE,
        Material.GREEN_CONCRETE,
        Material.BLUE_CONCRETE,
        Material.RED_NETHER_BRICK_STAIRS,
        Material.RESIN_BRICK_STAIRS,
        Material.MOSSY_COBBLESTONE_STAIRS,
        Material.OXIDIZED_CUT_COPPER_STAIRS,
        Material.END_STONE_BRICK_STAIRS,
        Material.WHITE_CONCRETE_POWDER, // Added for completeness
        Material.RED_CONCRETE_POWDER,
        Material.YELLOW_CONCRETE_POWDER,
        Material.GREEN_CONCRETE_POWDER,
        Material.BLUE_CONCRETE_POWDER
    )

    val lobbyGunLocations: MutableList<List<Double>> = mutableListOf()

    val boostLocations: MutableList<List<Double>> = mutableListOf()
    val boostChances: MutableMap<String, List<Int>> = mutableMapOf()

    val arenas: MutableList<ArenaSettings> = mutableListOf()
    val arenasById: MutableMap<String, ArenaSettings> = mutableMapOf()

    fun load(config: YamlConfiguration, logger: Logger) {
        lobbyWorldName = config.getString("lobby.world")
            ?: config.getString("lobby_name")
            ?: "world"

        defaultTemplateWorld = config.getString("game.default_template_world")
            ?: config.getString("map_name")
            ?: "sp_arena"

        maxParallelGamesPerArena = max(
            1,
            config.getInt(
                "game.max_parallel_games_per_arena",
                config.getInt("game.max_parallel_games", 1)
            )
        )

        gameDurationSeconds = max(1, config.getInt("game.duration_seconds", 300))
        returnToLobbyDelaySeconds = max(0, config.getInt("game.return_to_lobby_delay_seconds", 5))
        ceremonyEnabled = config.getBoolean("ceremony.enabled", false)

        // Preferred (CW-like) ceremony config:
        // ceremony:
        //   enabled: true
        //   template-name: Ceremony
        //   duration-seconds: 10
        //   wind-charges: 16
        //   podiums:
        //     - [x1, y, z1, x2, z2, yaw]
        ceremonyTemplateWorld =
            config.getString("ceremony.template-name")
                ?: config.getString("ceremony.template_name")
                ?: config.getString("ceremony.template_world")
                ?: "sp_ceremony"

        ceremonyDurationSeconds = max(
            0,
            config.getInt("ceremony.duration-seconds", config.getInt("ceremony.duration_seconds", 8))
        )

        ceremonyWindCharges = max(
            0,
            config.getInt("ceremony.wind-charges", config.getInt("ceremony.wind_charges", 0))
        )

        ceremonySpectatorSpawn = parseSpawnPoint(
            config.getList("ceremony.spectator-spawn")
                ?: config.getList("ceremony.spectator_spawn")
                ?: config.getList("ceremony.spectatorSpawn")
        )

        ceremonyPodiumsByPlace.clear()

        val podiumsAny = config.getList("ceremony.podiums") ?: emptyList<Any>()
        val listEntries = podiumsAny.filterIsInstance<List<*>>()
        if (listEntries.isNotEmpty()) {
            // New format: list entries are in order (place = index + 1).
            listEntries.forEachIndexed { idx, entry ->
                if (idx >= 4) return@forEachIndexed
                if (entry.size < 6) return@forEachIndexed

                val x1 = (entry[0] as? Number)?.toInt() ?: return@forEachIndexed
                val y = (entry[1] as? Number)?.toInt() ?: return@forEachIndexed
                val z1 = (entry[2] as? Number)?.toInt() ?: return@forEachIndexed
                val x2 = (entry[3] as? Number)?.toInt() ?: return@forEachIndexed
                val z2 = (entry[4] as? Number)?.toInt() ?: return@forEachIndexed
                val yaw = (entry[5] as? Number)?.toFloat() ?: return@forEachIndexed

                val minX = kotlin.math.min(x1, x2)
                val maxX = kotlin.math.max(x1, x2)
                val minZ = kotlin.math.min(z1, z2)
                val maxZ = kotlin.math.max(z1, z2)

                val centerX = (minX + maxX) / 2.0 + 0.5
                val centerZ = (minZ + maxZ) / 2.0 + 0.5

                val podium = CeremonyPodium(
                    place = idx + 1,
                    minX = minX,
                    minY = y,
                    minZ = minZ,
                    maxX = maxX,
                    maxY = y,
                    maxZ = maxZ,
                    spawn = SpawnPoint(centerX, y.toDouble(), centerZ, yaw, 0f)
                )
                ceremonyPodiumsByPlace[idx + 1] = podium
            }
        } else {
            // Legacy format (map list with place/min/max/spawn).
            val mapEntries = podiumsAny.filterIsInstance<Map<*, *>>()
            for (rawAny in mapEntries) {
                val raw = rawAny.entries.associate { it.key.toString() to it.value }

                val place = (raw["place"] as? Number)?.toInt() ?: continue
                val min = parseIntCoord3(raw["min"]) ?: continue
                val max = parseIntCoord3(raw["max"]) ?: continue
                val spawn = parseSpawnPoint(raw["spawn"])

                val (minX0, minY0, minZ0) = min
                val (maxX0, maxY0, maxZ0) = max

                val podium = CeremonyPodium(
                    place = place,
                    minX = kotlin.math.min(minX0, maxX0),
                    minY = kotlin.math.min(minY0, maxY0),
                    minZ = kotlin.math.min(minZ0, maxZ0),
                    maxX = kotlin.math.max(minX0, maxX0),
                    maxY = kotlin.math.max(minY0, maxY0),
                    maxZ = kotlin.math.max(minZ0, maxZ0),
                    spawn = spawn
                )
                ceremonyPodiumsByPlace[place] = podium
            }
        }

        scoreboardUpdateTicks = max(1, config.getLong("game.scoreboard_update_ticks", 10))
        actionbarUpdateTicks = max(1, config.getLong("game.actionbar_update_ticks", 5))

        inkMaxHp = max(1, config.getInt("ink.max_hp", 3))

        // Ink HP regeneration while player is in squid mode (INVISIBILITY).
        // Backward compatible with older key: ink.regen_on_own_color.*
        val legacyRegenPath = "ink.regen_on_own_color"
        inkRegenEnabled = config.getBoolean("ink.regen.enabled", config.getBoolean("$legacyRegenPath.enabled", true))
        inkRegenRatePerSecond = config.getDouble("ink.regen.rate_per_second", config.getDouble("$legacyRegenPath.rate_per_second", 0.5)).coerceAtLeast(0.0)
        inkRegenDelayAfterDamageSeconds = max(
            0,
            config.getInt("ink.regen.delay_after_damage_seconds", config.getInt("$legacyRegenPath.delay_after_damage_seconds", 2))
        )

        spawnProtectionAfterRespawnSeconds = max(0, config.getInt("spawn_protection.after_respawn_seconds", 4))
        spawnProtectionResistanceDurationTicks = max(0, config.getInt("spawn_protection.resistance_duration_ticks", 60))
        spawnProtectionResistanceAmplifier = max(0, config.getInt("spawn_protection.resistance_amplifier", 10))
        spawnProtectionNoDamageTicks = max(0, config.getInt("spawn_protection.no_damage_ticks", 60))

        gunVelocity = config.getDouble("weapons.gun.velocity", 1.4).coerceAtLeast(0.0)
        gunDisableGravity = config.getBoolean("weapons.gun.disable_gravity", true)

        bombVelocity = config.getDouble("weapons.bomb.velocity", 1.1).coerceAtLeast(0.0)
        bombHorizontalMultiplier = config.getDouble("weapons.bomb.horizontal_multiplier", 0.85).coerceIn(0.05, 5.0)
        bombUpwardBoost = config.getDouble("weapons.bomb.upward_boost", 0.55).coerceIn(-5.0, 5.0)
        gunPaintRadius = config.getDouble("weapons.gun.paint_radius", 1.5)
        gunPaintInLobbyRadius = config.getDouble("weapons.gun.paint_in_lobby_radius", 1.0).coerceAtLeast(0.5)
        bombPaintRadius = config.getDouble("weapons.bomb.paint_radius", 5.0)
        gunKillPaintRadius = config.getDouble("weapons.gun.kill_paint_radius", 3.0)
        bombKillPaintRadius = config.getDouble("weapons.bomb.kill_paint_radius", 5.0)

        boostsEnabled = config.getBoolean("boosts.enabled", true)
        boostsMinIntervalSeconds = max(1, config.getInt("boosts.min_interval_seconds", 18))
        boostsMaxIntervalSeconds = max(boostsMinIntervalSeconds, config.getInt("boosts.max_interval_seconds", 39))
        boostsBombPercent = config.getInt("boosts.bomb_percent", 70).coerceIn(0, 100)

        lobbyGunLocations.clear()
        val gunStandList = config.getList("lobby.gun_stand_locations") ?: emptyList<Any>()
        for (item in gunStandList){
            val coord = parseCoord3(item) ?: continue
            lobbyGunLocations.add(coord)
        }
        if (lobbyGunLocations.isEmpty()){
            logger.info("lobby.gun_stand_locations is empty; gun stands will not be spawned")
        }

        boostLocations.clear()
        val locList = config.getList("boosts.locations") ?: config.getList("boost_locations") ?: emptyList<Any>()
        for (item in locList) {
            val coord = parseCoord3(item) ?: continue
            boostLocations.add(coord)
        }
        if (boostLocations.isEmpty()) {
            boostLocations.add(listOf(0.0, 0.0, 0.0))
            logger.warning("boosts.locations is empty; using fallback [0,0,0]")
        }



        boostChances.clear()
        val bombChance = config.getInt("boosts.chances.bomb", 0).coerceAtLeast(0)
        if (bombChance != 0) {
            boostChances.put("bomb", listOf(boostChancesSum, boostChancesSum + bombChance))
            boostChancesSum = boostChancesSum + bombChance + 1
        }
        val bacillusChance = config.getInt("boosts.chances.bacillus", 0).coerceAtLeast(0)
        if (bacillusChance != 0) {
            boostChances.put("bacillus", listOf(boostChancesSum, boostChancesSum + bacillusChance))
            boostChancesSum = boostChancesSum + bacillusChance + 1
        }
        if (boostChances.isEmpty()) {
            logger.warning("Map of boost chances is empty: whether there is no chances in config or all chances are 0 or less. Boosts won't be enabled.")
        }

        bacillusDurationSeconds = max(0, config.getInt("bacillus.duration_seconds", 5))
        bacillusRangeBlocks = config.getDouble("bacillus.range_blocks", 3.2).coerceAtLeast(0.0)
        bacillusCooldownMs = max(0, config.getLong("bacillus.cooldown_ms", 250))

        defaultSkin = config.getInt("skins.default", 1000)
        val item = ItemStack(Material.CROSSBOW, 1)
        val meta = item.itemMeta
        meta.displayName(Component.text("Сплат-пушка").color(TextColor.color(0xFF55FF)))
        meta.persistentDataContainer.set(
            NamespacedKey(SplatoonPlugin.instance, "splatGun"),
            PersistentDataType.BOOLEAN,
            true
        )
        item.itemMeta = meta
        val models = config.getList("skins.models") ?: emptyList<Any>()
        if (models.isNotEmpty()) {
            val size = (ceil(models.size / 9.0) * 9).toInt().coerceAtLeast(9)
            @Suppress("DEPRECATION")
            skinsInventory = Bukkit.createInventory(null, size, "Установить скин")
            var cell = 0
            for (any in models) {
                try {
                    val id = any.toString().toInt()
                    val copy = item.clone()
                    val copyMeta = copy.itemMeta
                    copyMeta.setCustomModelData(id)
                    copy.itemMeta = copyMeta
                    skinsInventory?.setItem(cell, copy)
                    cell += 1
                } catch (_: Exception) {}
            }
        }


        sneakOnInkEnabled = config.getBoolean("movement.sneak_on_ink.enabled", true)
        sneakOnInkScanSteps = max(1, config.getInt("movement.sneak_on_ink.scan_steps", 3))
        sneakOnInkScanStepBlocks = config.getDouble("movement.sneak_on_ink.scan_step_blocks", 0.1).coerceAtLeast(0.01)
        sneakOnInkSpeedAmplifier = config.getInt("movement.sneak_on_ink.speed_amplifier", 18).coerceIn(0, 255)
        sneakOnInkInvisibilityAmplifier = config.getInt("movement.sneak_on_ink.invisibility_amplifier", 1).coerceIn(-1, 255)
        sneakOnInkRegenerationAmplifier = config.getInt("movement.sneak_on_ink.regeneration_amplifier", 1).coerceIn(-1, 255)
        sneakOnInkEffectDurationTicks = max(1, config.getInt("movement.sneak_on_ink.effect_duration_ticks", 2))
        sneakOnInkTaskPeriodTicks = max(1, config.getLong("movement.sneak_on_ink.task_period_ticks", 1))

        speedupOnIceEnabled = config.getBoolean("movement.speedup_on_ice.enabled", true)
        speedupOnIceAmplifier = config.getInt("movement.speedup_on_ice.amplifier", 2).coerceIn(0, 255)
        jumpPadJumpAmplifier = config.getInt("movement.jump_pads.jump_amplifier", 5).coerceIn(0, 255)
        jumpPadEffectDuration = config.getInt("movement.jump_pads.effect_duration", 200)
        jumpPadBlockType = config.getString("movement.jump_pads.jump_pad_block_type", "LIME_CONCRETE_POWDER") ?: "LIME_CONCRETE_POWDER"


        arenas.clear()
        arenasById.clear()

        val arenasRaw = config.getMapList("arenas") ?: emptyList<Map<String, Any>>()
        for (raw in arenasRaw) {
            val id = raw["id"] as? String ?: continue
            val world = raw["world"] as? String ?: continue
            val teamCount = (raw["teamCount"] as? Int) ?: 2
            val playersPerTeam = (raw["playersPerTeam"] as? Int) ?: 3

            val rawInstances = (raw["instances"] as? Int)
                ?: (raw["maxParallelGames"] as? Int)
                ?: maxParallelGamesPerArena
            val instances = max(1, rawInstances)

            val spawns = parseArenaSpawns(raw["spawns"], logger)
            val s = ArenaSettings(id, world, teamCount, playersPerTeam, spawns, instances)
            arenas.add(s)
            arenasById[id] = s
        }
    }

    private fun parseIntCoord3(item: Any?): Triple<Int, Int, Int>? {
        if (item !is List<*>) return null
        if (item.size < 3) return null
        return try {
            val x = (item[0] as Number).toInt()
            val y = (item[1] as Number).toInt()
            val z = (item[2] as Number).toInt()
            Triple(x, y, z)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseCoord3(item: Any?): List<Double>? {
        if (item !is List<*>) return null
        if (item.size < 3) return null
        return try {
            val x = (item[0] as Number).toDouble()
            val y = (item[1] as Number).toDouble()
            val z = (item[2] as Number).toDouble()
            listOf(x, y, z)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseArenaSpawns(raw: Any?, logger: Logger): List<SpawnPoint> {
        val result = mutableListOf<SpawnPoint>()

        val list = raw as? List<*> ?: return result.toList()

        for (pAny in list) {
            val sp = parseSpawnPoint(pAny)

            if (sp != null) {
                result.add(sp)
            }
        }

        if (list.isNotEmpty() && result.isEmpty()) {
            logger.warning("Arena spawns section exists but no valid points were parsed.")
        }

        return result.toList()
    }

    private fun parseSpawnPoint(raw: Any?): SpawnPoint? {
        if (raw !is List<*>) return null
        if (raw.size < 3) return null
        return try {
            val x = (raw[0] as Number).toDouble()
            val y = (raw[1] as Number).toDouble()
            val z = (raw[2] as Number).toDouble()
            val yaw = if (raw.size >= 4) (raw[3] as Number).toFloat() else null
            val pitch = if (raw.size >= 5) (raw[4] as Number).toFloat() else null
            SpawnPoint(x, y, z, yaw, pitch)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseTeamKey(raw: Any?): Int? {
        return when (raw) {
            is Number -> raw.toInt()
            is String -> {
                raw.toIntOrNull()
                    ?: when (raw.lowercase()) {
                        "red", "r" -> 0
                        "yellow", "y" -> 1
                        "green", "g" -> 2
                        "blue", "b" -> 3
                        else -> null
                    }
            }
            else -> null
        }
    }
}
