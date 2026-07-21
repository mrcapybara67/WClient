package com.retrivedmods.wclient.game.module.visual

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import com.retrivedmods.wclient.overlay.hud.ChunkFinderOverlay
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket
import kotlin.random.Random

class ChunkFinderModule : Module("ChunkFinder", ModuleCategory.Visual) {

    // === TARGET BLOCK SETTINGS ===
    private val enableCobbledDeepslate by boolValue("Cobbled Deepslate", true)
    private val enableEndStone by boolValue("End Stone", true)
    private val enableDeepslate by boolValue("Deepslate", true)
    private val enableOtherOres by boolValue("Other Ores", false)

    // === OVERLAY SETTINGS ===
    private val overlayMode by enumValue("Overlay Mode", OverlayMode.THIN_BOX, OverlayMode::class.java)
    private val boxThickness by floatValue("Box Thickness", 1.8f, 0.5f..5.0f)
    private val overlayOpacity by floatValue("Opacity", 0.85f, 0.1f..1.0f)
    private val maxRenderDistance by intValue("Render Distance", 64, 16..128)
    private val maxBlocksTracked by intValue("Max Blocks", 500, 50..2000)
    private val fadeDistance by intValue("Fade Distance", 48, 16..128)

    // === COLORS ===
    private val cobbledDeepslateColorR by intValue("CD Color R", 80, 0..255)
    private val cobbledDeepslateColorG by intValue("CD Color G", 78, 0..255)
    private val cobbledDeepslateColorB by intValue("CD Color B", 90, 0..255)

    private val endStoneColorR by intValue("ES Color R", 255, 0..255)
    private val endStoneColorG by intValue("ES Color G", 255, 0..255)
    private val endStoneColorB by intValue("ES Color B", 190, 0..255)

    private val deepslateColorR by intValue("DS Color R", 65, 0..255)
    private val deepslateColorG by intValue("DS Color G", 60, 0..255)
    private val deepslateColorB by intValue("DS Color B", 72, 0..255)

    private val otherOreColorR by intValue("Other Color R", 255, 0..255)
    private val otherOreColorG by intValue("Other Color G", 185, 0..255)
    private val otherOreColorB by intValue("Other Color B", 0, 0..255)

    // === VISUAL OPTIONS ===
    private val showCoordinates by boolValue("Show Coords", true)
    private val showBlockName by boolValue("Show Block Name", true)
    private val glowEffect by boolValue("Glow Effect", true)
    private val smoothFade by boolValue("Smooth Fade", true)
    private val chunkBorderLines by boolValue("Chunk Borders", false)

    // === BYPASS OPTIONS (DonutSMP) ===
    private val bypassMode by boolValue("Bypass Mode", false)
    private val randomizeCheck by boolValue("Randomize Check", true)
    private val minCheckDelay by intValue("Min Check (ms)", 50, 10..500)
    private val maxCheckDelay by intValue("Max Check (ms)", 150, 50..1000)
    private val skipChance by floatValue("Skip Chance %", 5f, 0f..50f)

    // === TRACKED BLOCKS ===
    data class TrackedBlock(
        val position: Vector3i,
        val type: BlockType,
        val discoveredTime: Long,
        val blockName: String = ""
    )

    enum class BlockType {
        COBBLED_DEEPSLATE,
        END_STONE,
        DEEPSLATE,
        OTHER_ORE
    }

    enum class OverlayMode {
        THIN_BOX,
        FILLED_BOX,
        CORNER_BOX,
        OUTLINE_ONLY
    }

    private val trackedBlocks = mutableListOf<TrackedBlock>()
    private val discoveredCounts = mutableMapOf<BlockType, Int>()
    private var lastPacketTime = 0L
    private var lastCleanupTime = 0L
    private val cleanupInterval = 10000L

    // Target block identifiers (lowercase, partial match)
    private val cobbledDeepslateIds = setOf("cobbled_deepslate", "cobbleddeepslate")
    private val endStoneIds = setOf("end_stone", "endstone")
    private val deepslateIds = setOf(
        "deepslate", "polished_deepslate", "deepslate_bricks",
        "deepslate_tiles", "cracked_deepslate_bricks", "cracked_deepslate_tiles",
        "chiseled_deepslate", "deepslate_coal_ore", "deepslate_iron_ore",
        "deepslate_copper_ore", "deepslate_gold_ore", "deepslate_redstone_ore",
        "deepslate_emerald_ore", "deepslate_lapis_ore", "deepslate_diamond_ore"
    )
    private val otherOreIds = setOf(
        "diamond_ore", "emerald_ore", "ancient_debris",
        "gold_ore", "iron_ore", "lapis_ore", "redstone_ore",
        "coal_ore", "copper_ore", "nether_quartz_ore",
        "nether_gold_ore", "netherite_scrap"
    )

    override fun onEnabled() {
        super.onEnabled()
        try {
            if (isSessionCreated) {
                ChunkFinderOverlay.setOverlayEnabled(true)
                updateOverlaySettings()
                trackedBlocks.clear()
                discoveredCounts.clear()
            }
        } catch (e: Exception) {
            println("Error enabling ChunkFinder: ${e.message}")
        }
    }

    override fun onDisabled() {
        super.onDisabled()
        try {
            ChunkFinderOverlay.setOverlayEnabled(false)
            ChunkFinderOverlay.setTrackedBlocks(emptyList())
        } catch (_: Exception) {}
        trackedBlocks.clear()
        discoveredCounts.clear()
    }

    override fun onDisconnect(reason: String) {
        ChunkFinderOverlay.setOverlayEnabled(false)
        ChunkFinderOverlay.setTrackedBlocks(emptyList())
        trackedBlocks.clear()
        discoveredCounts.clear()
    }

    private fun updateOverlaySettings() {
        ChunkFinderOverlay.setOverlayMode(overlayMode)
        ChunkFinderOverlay.setBoxThickness(boxThickness)
        ChunkFinderOverlay.setOverlayOpacity(overlayOpacity)
        ChunkFinderOverlay.setMaxRenderDistance(maxRenderDistance)
        ChunkFinderOverlay.setFadeDistance(fadeDistance)
        ChunkFinderOverlay.setShowCoordinates(showCoordinates)
        ChunkFinderOverlay.setShowBlockName(showBlockName)
        ChunkFinderOverlay.setGlowEffect(glowEffect)
        ChunkFinderOverlay.setSmoothFade(smoothFade)
        ChunkFinderOverlay.setChunkBorderLines(chunkBorderLines)
        ChunkFinderOverlay.setBlockTypeColors(
            cobbledDeepslateColorR, cobbledDeepslateColorG, cobbledDeepslateColorB,
            endStoneColorR, endStoneColorG, endStoneColorB,
            deepslateColorR, deepslateColorG, deepslateColorB,
            otherOreColorR, otherOreColorG, otherOreColorB
        )
    }

    /**
     * Resolve block identifier from UpdateBlockPacket using runtime ID + block mapping
     */
    private fun resolveBlockIdentifier(packet: UpdateBlockPacket): String? {
        return try {
            if (isSessionCreated) {
                val runtimeId = packet.definition.runtimeId
                // Use try-catch because blockMapping might not be initialized yet
                try {
                    session.blockMapping.getDefinition(runtimeId)?.identifier
                } catch (_: Exception) {
                    packet.definition.toString()
                }
            } else {
                packet.definition.toString()
            }
        } catch (_: Exception) {
            try {
                packet.definition.toString()
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun classifyBlock(identifier: String): BlockType? {
        val id = identifier.lowercase()
        val cleanId = id.substringAfter("minecraft:").substringAfter(":")

        return when {
            enableCobbledDeepslate && cobbledDeepslateIds.any { cleanId.contains(it) } ->
                BlockType.COBBLED_DEEPSLATE
            enableEndStone && endStoneIds.any { cleanId.contains(it) } ->
                BlockType.END_STONE
            enableDeepslate && deepslateIds.any { cleanId.contains(it) } ->
                BlockType.DEEPSLATE
            enableOtherOres && otherOreIds.any { cleanId.contains(it) } ->
                BlockType.OTHER_ORE
            else -> null
        }
    }

    private fun shouldProcessPacket(): Boolean {
        if (!bypassMode) return true
        if (skipChance > 0 && Random.nextFloat() * 100 < skipChance) return false
        if (randomizeCheck) {
            val now = System.currentTimeMillis()
            val effectiveDelay = Random.nextLong(minCheckDelay.toLong(), maxCheckDelay.toLong())
            if (now - lastPacketTime < effectiveDelay) return false
            lastPacketTime = now
        }
        return true
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled || !isSessionCreated) return

        val packet = interceptablePacket.packet

        // Process UpdateBlockPacket for block detection
        if (packet is UpdateBlockPacket) {
            if (!shouldProcessPacket()) return

            try {
                val blockPos = packet.blockPosition
                val identifier = resolveBlockIdentifier(packet) ?: return

                val blockType = classifyBlock(identifier)
                if (blockType != null) {
                    val pos = Vector3i.from(blockPos.x, blockPos.y, blockPos.z)

                    // Don't add duplicates
                    if (trackedBlocks.none { it.position == pos }) {
                        val blockName = identifier.substringAfter("minecraft:").substringAfter(":")
                        val tracked = TrackedBlock(pos, blockType, System.currentTimeMillis(), blockName)
                        trackedBlocks.add(tracked)
                        discoveredCounts[blockType] = (discoveredCounts[blockType] ?: 0) + 1

                        // Limit tracked blocks
                        if (trackedBlocks.size > maxBlocksTracked) {
                            trackedBlocks.removeAt(0)
                        }

                        // Notify player
                        session.displayClientMessage(
                            "§l§b[ChunkFinder] §r§7Found §e$blockName §7at §f${pos.x} ${pos.y} ${pos.z}"
                        )

                        updateTrackedBlocks()
                    }
                }
            } catch (_: Exception) {
                // Ignore parsing errors silently
            }
        }

        // Periodic cleanup and update on PlayerAuthInputPacket
        if (packet is PlayerAuthInputPacket) {
            val now = System.currentTimeMillis()
            val player = session.localPlayer

            // Update camera state for overlay rendering
            ChunkFinderOverlay.setCameraState(
                yaw = player.rotationYaw,
                pitch = player.rotationPitch,
                x = player.posX,
                y = player.posY,
                z = player.posZ
            )

            // Periodic cleanup
            if (now - lastCleanupTime >= cleanupInterval) {
                cleanupDistantBlocks()
                lastCleanupTime = now
            }

            updateTrackedBlocks()
        }
    }

    private fun cleanupDistantBlocks() {
        if (!isSessionCreated) return
        val playerPos = session.localPlayer.vec3Position
        val px = playerPos.x.toInt()
        val pz = playerPos.z.toInt()
        val beforeCount = trackedBlocks.size
        trackedBlocks.removeIf { block ->
            val dx = block.position.x - px
            val dz = block.position.z - pz
            val dist = Math.sqrt((dx * dx + dz * dz).toDouble())
            dist > maxRenderDistance * 1.5
        }
        if (trackedBlocks.size < beforeCount) {
            updateTrackedBlocks()
        }
    }

    private fun updateTrackedBlocks() {
        if (!isSessionCreated) return

        val playerPos = session.localPlayer.vec3Position
        val px = playerPos.x.toFloat()
        val pz = playerPos.z.toFloat()

        val blockData = trackedBlocks.mapNotNull { block ->
            val dx = block.position.x - px
            val dz = block.position.z - pz
            val dist = Math.sqrt((dx * dx + dz * dz).toDouble()).toFloat()
            if (dist > maxRenderDistance) return@mapNotNull null

            val alpha = if (smoothFade && dist > fadeDistance) {
                val fadeProgress = (dist - fadeDistance) / (maxRenderDistance - fadeDistance).toFloat()
                (1f - fadeProgress).coerceIn(0.15f, 1f)
            } else {
                1f
            }

            ChunkFinderOverlay.BlockOverlayData(
                blockX = block.position.x,
                blockY = block.position.y,
                blockZ = block.position.z,
                relativeX = dx,
                relativeZ = dz,
                distance = dist,
                blockType = block.type.name,
                blockName = block.blockName,
                discoveredTime = block.discoveredTime,
                alpha = alpha
            )
        }

        ChunkFinderOverlay.setTrackedBlocks(blockData)
        updateOverlaySettings()
    }

    fun getDiscoveryStats(): Map<BlockType, Int> = discoveredCounts.toMap()
}
