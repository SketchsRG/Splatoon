package ru.joutak.splatoon.listeners

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.type.Slab
import org.bukkit.block.data.type.Stairs
import org.bukkit.block.data.Bisected
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import ru.joutak.splatoon.config.SplatoonSettings
import ru.joutak.splatoon.scripts.Game
import ru.joutak.splatoon.scripts.GameManager
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor

enum class BlockType { STAIRS, SLAB, FULL }

data class StairState(val facing: org.bukkit.block.BlockFace, val half: Bisected.Half, val shape: Stairs.Shape, val waterlogged: Boolean)
data class SlabState(val type: Slab.Type, val waterlogged: Boolean)

class ProjectileHitListener : Listener {

    private val ceremonyKey = "ceremonyKey"
    private val lastShooterHitMs = mutableMapOf<UUID, Long>()

    private fun getBlockType(block: Block): BlockType {
        val name = block.type.name
        return when {
            name.endsWith("_STAIRS") -> BlockType.STAIRS
            name.endsWith("_SLAB") -> BlockType.SLAB
            else -> BlockType.FULL
        }
    }

    private fun getTeamMaterial(team: Int, blockType: BlockType): Material {
        return when(blockType) {
            BlockType.STAIRS -> when(team) {
                0 -> Material.RED_NETHER_BRICK_STAIRS
                1 -> Material.RESIN_BRICK_STAIRS
                2 -> Material.MOSSY_COBBLESTONE_STAIRS
                3 -> Material.OXIDIZED_CUT_COPPER_STAIRS
                else -> Material.END_STONE_BRICK_STAIRS
            }
            BlockType.SLAB -> when(team) {
                0 -> Material.RED_NETHER_BRICK_SLAB
                1 -> Material.RESIN_BRICK_SLAB
                2 -> Material.MOSSY_COBBLESTONE_SLAB
                3 -> Material.OXIDIZED_CUT_COPPER_SLAB
                else -> Material.END_STONE_BRICK_SLAB
            }
            BlockType.FULL -> when(team) {
                0 -> Material.RED_CONCRETE
                1 -> Material.YELLOW_CONCRETE
                2 -> Material.GREEN_CONCRETE
                3 -> Material.BLUE_CONCRETE
                else -> Material.WHITE_CONCRETE
            }
        }
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

    private fun getStairsState(block: Block): StairState? {
        val data = block.blockData as? Stairs ?: return null
        return StairState(data.facing, data.half, data.shape, data.isWaterlogged)
    }

    private fun applyStairState(block: Block, state: StairState) {
        val data = block.blockData as? Stairs ?: return
        data.facing = state.facing
        data.half = state.half
        data.shape = state.shape
        data.isWaterlogged = state.waterlogged
        block.blockData = data
    }

    private fun getSlabState(block: Block): SlabState? {
        val data = block.blockData as? Slab ?: return null
        return SlabState(data.type, data.isWaterlogged)
    }

    private fun applySlabState(block: Block, state: SlabState) {
        val data = block.blockData as? Slab ?: return
        data.type = state.type
        data.isWaterlogged = state.waterlogged
        block.blockData = data
    }

    @EventHandler
    fun projectileHitEvent(event: ProjectileHitEvent) {
        val entity = event.entity
        if (entity.type != EntityType.SNOWBALL) return

        if (entity.hasMetadata(ceremonyKey)) {
            runCatching { entity.passengers.toList().forEach { it.remove() } }
            entity.remove()
            return
        }

        if (!entity.hasMetadata("paintKey")) return
        runCatching { entity.passengers.toList().forEach { it.remove() } }

        val shooterUuid = getShooterUuid(entity.getMetadata("shooterId").firstOrNull()?.asString())
        val shooter = if (shooterUuid != null) Bukkit.getPlayer(shooterUuid) else null
        if (shooter == null) return

        val isInLobby = GameManager.isLobbyWorld(shooter.world)
        if (isInLobby) {
            val hitBlock = event.hitBlock
            val hitFace = event.hitBlockFace

            if (hitBlock != null && hitFace != null) {
                val paintTeam = entity.getMetadata("paintTeam").firstOrNull()?.asInt() ?: return
                val radius = SplatoonSettings.gunPaintInLobbyRadius
                val center = hitBlock.getRelative(hitFace).location
                
                safePaintInRadius(center, entity.world, radius, paintTeam)
                entity.world.playSound(center, Sound.ENTITY_SLIME_SQUISH_SMALL, 0.7f, 1.55f)
            }
            entity.remove()
            return
        }

        val game = GameManager.playerGame[shooter.uniqueId] ?: return
        val shooterTeam = game.commands[shooter.uniqueId] ?: return

        val paintTeam = entity.getMetadata("paintTeam").firstOrNull()?.asInt() ?: shooterTeam
        val isBomb = entity.hasMetadata("bombKey")
        val radius = if (isBomb) SplatoonSettings.bombPaintRadius else SplatoonSettings.gunPaintRadius
        val killPaintRadius = if (isBomb) radius + 1.5 else SplatoonSettings.gunKillPaintRadius
        val damagePerHit = if (isBomb) SplatoonSettings.inkMaxHp else 1

        val hitEntity = event.hitEntity
        val hitBlock = event.hitBlock
        val hitFace = event.hitBlockFace

        if (hitEntity is Player) {
            val victim = hitEntity
            val victimGame = GameManager.playerGame[victim.uniqueId]
            if (victimGame == null || victimGame != game) {
                explosivePaint(radius, victim.location, entity.world, game, shooter.uniqueId, paintTeam, null)
                return
            }

            val victimTeam = game.commands[victim.uniqueId]
            if (victimTeam == null) {
                explosivePaint(radius, victim.location, entity.world, game, shooter.uniqueId, paintTeam, null)
                return
            }

            if (isBomb) {
                victim.world.playSound(victim.location, Sound.ENTITY_GENERIC_EXPLODE, 1.8f, 1.0f)
                victim.world.playSound(victim.location, Sound.ENTITY_SLIME_SQUISH_SMALL, 1.0f, 0.8f)
            } else {
                victim.world.playSound(victim.location, Sound.ENTITY_SLIME_SQUISH_SMALL, 0.7f, 1.55f)
            }

            val victimProtectedByInk = victim.hasPotionEffect(PotionEffectType.INVISIBILITY)
            val spawnSafe = game.isSpawnSafe(victim)

            val excludeUnder = if (victimProtectedByInk && paintTeam != victimTeam) victim.location.block else null
            explosivePaint(radius, victim.location, entity.world, game, shooter.uniqueId, paintTeam, excludeUnder)

            if (victimProtectedByInk || spawnSafe) return
            if (paintTeam == victimTeam) return
            if (shooterTeam == victimTeam) return

            val hpLeft = game.damageInkHp(victim.uniqueId, damagePerHit)
            playHitMarker(shooter, victim, hpLeft, game)

            if (hpLeft <= 0) {
                game.kills[shooter.uniqueId] = (game.kills[shooter.uniqueId] ?: 0) + 1
                val deathLoc = victim.location.clone()
                splatAndRespawn(victim, game)
                explosivePaint(killPaintRadius, deathLoc, entity.world, game, shooter.uniqueId, paintTeam, null)
            }
            return
        }

        if (hitBlock == null || hitFace == null) return

        val center = hitBlock.getRelative(hitFace).location

        if (!isBomb) {
            entity.world.playSound(center, Sound.ENTITY_SLIME_SQUISH_SMALL, 0.7f, 1.55f)
            explosivePaint(radius, center, entity.world, game, shooter.uniqueId, paintTeam, null)
            return
        }

        entity.world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 2.1f, 1.0f)
        entity.world.playSound(center, Sound.ENTITY_SLIME_SQUISH_SMALL, 1.2f, 0.75f)

        val victims = entity.world.getNearbyEntities(center, radius, radius, radius)
            .filterIsInstance<Player>()
            .filter { GameManager.playerGame[it.uniqueId] == game }

        val protectedSnapshot = mutableMapOf<UUID, Boolean>()
        victims.forEach { v ->
            protectedSnapshot[v.uniqueId] = v.hasPotionEffect(PotionEffectType.INVISIBILITY) || game.isSpawnSafe(v)
        }

        explosivePaint(radius, center, entity.world, game, shooter.uniqueId, paintTeam, null)

        victims.forEach { victim ->
            val victimTeam = game.commands[victim.uniqueId] ?: return@forEach
            val protected = protectedSnapshot[victim.uniqueId] == true
            if (protected) return@forEach
            if (paintTeam == victimTeam) return@forEach
            if (shooterTeam == victimTeam) return@forEach

            val hpLeft = game.damageInkHp(victim.uniqueId, damagePerHit)
            playHitMarker(shooter, victim, hpLeft, game)

            if (hpLeft <= 0) {
                game.kills[shooter.uniqueId] = (game.kills[shooter.uniqueId] ?: 0) + 1
                val deathLoc = victim.location.clone()
                splatAndRespawn(victim, game)
                explosivePaint(killPaintRadius, deathLoc, entity.world, game, shooter.uniqueId, paintTeam, null)
            }
        }
    }

    private fun playHitMarker(shooter: Player, victim: Player, victimHpLeft: Int, game: Game) {
        val now = System.currentTimeMillis()
        val last = lastShooterHitMs[shooter.uniqueId] ?: 0L
        if (now - last >= 120L) {
            shooter.playSound(shooter.location, Sound.ENTITY_ARROW_HIT_PLAYER, 0.7f, 1.6f)
            lastShooterHitMs[shooter.uniqueId] = now

            game.pushActionBarOverlay(
                shooter.uniqueId,
                Component.text("✦ HIT ", NamedTextColor.GREEN)
                    .append(Component.text("(${victimHpLeft}/${SplatoonSettings.inkMaxHp})", NamedTextColor.GRAY))
            )
        }

        victim.playSound(victim.location, Sound.ENTITY_PLAYER_HURT, 0.6f, 1.1f)
        game.pushActionBarOverlay(
            victim.uniqueId,
            Component.text("✹ HIT ", NamedTextColor.RED)
                .append(Component.text("(${victimHpLeft}/${SplatoonSettings.inkMaxHp})", NamedTextColor.GRAY))
        )

        game.syncHealthBar(victim)
    }

    private fun splatAndRespawn(player: Player, game: Game) {
        game.resetInkHp(player.uniqueId)
        player.activePotionEffects.forEach { e -> player.removePotionEffect(e.type) }
        game.teleportToSpawn(player)
        game.setSpawnProtection(player, SplatoonSettings.spawnProtectionAfterRespawnSeconds * 1000L)
        game.syncHealthBar(player)
        player.velocity = player.velocity.zero()
        player.fireTicks = 0
        player.foodLevel = 20
        player.saturation = 20f

        if (SplatoonSettings.spawnProtectionResistanceDurationTicks > 0) {
            player.addPotionEffect(
                PotionEffect(
                    PotionEffectType.RESISTANCE,
                    SplatoonSettings.spawnProtectionResistanceDurationTicks,
                    SplatoonSettings.spawnProtectionResistanceAmplifier,
                    false,
                    false,
                    false
                )
            )
        }
        player.noDamageTicks = SplatoonSettings.spawnProtectionNoDamageTicks
    }

    private fun getShooterUuid(str: String?): UUID? {
        if (str.isNullOrBlank()) return null
        return try {
            UUID.fromString(str)
        } catch (_: Exception) {
            null
        }
    }

    private fun explosivePaint(
        r: Double,
        location: org.bukkit.Location,
        world: World,
        game: Game,
        shooterId: UUID,
        paintTeam: Int,
        exclude: Block?
    ) {
        val blocks = mutableListOf<Block>()
        for (x in roundFromZero(location.x - r)..roundFromZero(location.x + r)) {
            for (y in roundFromZero(location.y - r)..roundFromZero(location.y + r)) {
                for (z in roundFromZero(location.z - r)..roundFromZero(location.z + r)) {
                    val b = world.getBlockAt(x, y, z)
                    if (b.type != Material.AIR && b.location.distance(location) <= r) blocks.add(b)
                }
            }
        }

        val paintable = SplatoonSettings.paintableMaterials

        for (b in blocks) {
            if (exclude != null && b.x == exclude.x && b.y == exclude.y && b.z == exclude.z) continue
            if (!paintable.contains(b.type)) continue
            
            val blockType = getBlockType(b)
            val newMat = getTeamMaterial(paintTeam, blockType)
            if (b.type == newMat) continue

            val oldState: Any? = when(blockType) {
                BlockType.STAIRS -> getStairsState(b)
                BlockType.SLAB -> getSlabState(b)
                BlockType.FULL -> null
            }

            val oldTeam = getTeamFromMaterial(b.type)
            if (oldTeam != null) {
                game.paintedCommand[oldTeam] = (game.paintedCommand[oldTeam] ?: 0) - 1
            }

            b.type = newMat

            when(oldState) {
                is StairState -> applyStairState(b, oldState)
                is SlabState -> applySlabState(b, oldState)
            }

            val shooterBaseTeam = game.commands[shooterId]
            if (shooterBaseTeam != null) {
                val delta = when {
                    oldTeam == shooterBaseTeam && paintTeam != shooterBaseTeam -> -1
                    oldTeam != shooterBaseTeam && paintTeam == shooterBaseTeam -> 1
                    else -> 0
                }
                if (delta != 0) game.paintedPerson[shooterId] = (game.paintedPerson[shooterId] ?: 0) + delta
            }

            game.paintedCommand[paintTeam] = (game.paintedCommand[paintTeam] ?: 0) + 1
        }
    }

    private fun roundFromZero(n: Double): Int {
        return when {
            n > 0 -> ceil(n).toInt()
            n < 0 -> floor(n).toInt()
            else -> n.toInt()
        }
    }

    private fun safePaintInRadius(
        center: Location,
        world: World,
        radius: Double,
        paintTeam: Int,
    ) {
        val paintable = SplatoonSettings.paintableMaterials

        if (radius <= 0.6) {
            val block = center.block
            if (paintable.contains(block.type)) {
                val blockType = getBlockType(block)
                val newMat = getTeamMaterial(paintTeam, blockType)
                if (block.type != newMat) {
                    val oldState: Any? = when(blockType) {
                        BlockType.STAIRS -> getStairsState(block)
                        BlockType.SLAB -> getSlabState(block)
                        BlockType.FULL -> null
                    }
                    block.type = newMat
                    when(oldState) {
                        is StairState -> applyStairState(block, oldState)
                        is SlabState -> applySlabState(block, oldState)
                    }
                }
            }
            return
        }

        for (x in roundFromZero(center.x - radius)..roundFromZero(center.x + radius)) {
            for (y in roundFromZero(center.y - radius)..roundFromZero(center.y + radius)) {
                for (z in roundFromZero(center.z - radius)..roundFromZero(center.z + radius)) {
                    val block = world.getBlockAt(x, y, z)
                    if (paintable.contains(block.type)) {
                        val blockType = getBlockType(block)
                        val newMat = getTeamMaterial(paintTeam, blockType)
                        if (block.type != newMat) {
                            val oldState: Any? = when(blockType) {
                                BlockType.STAIRS -> getStairsState(block)
                                BlockType.SLAB -> getSlabState(block)
                                BlockType.FULL -> null
                            }
                            block.type = newMat
                            when(oldState) {
                                is StairState -> applyStairState(block, oldState)
                                is SlabState -> applySlabState(block, oldState)
                            }
                        }
                    }
                }
            }
        }
    }
}
