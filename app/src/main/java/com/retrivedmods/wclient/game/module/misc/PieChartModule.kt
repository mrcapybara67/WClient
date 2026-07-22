package com.retrivedmods.wclient.game.module.misc

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import com.retrivedmods.wclient.overlay.hud.PieChartOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket
import org.cloudburstmc.protocol.bedrock.packet.AddItemEntityPacket
import org.cloudburstmc.protocol.bedrock.packet.AddPlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.LevelSoundEventPacket
import org.cloudburstmc.protocol.bedrock.packet.MobEffectPacket
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityAbsolutePacket
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityDeltaPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.RemoveEntityPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket
import org.cloudburstmc.protocol.bedrock.packet.BlockEntityDataPacket
import kotlin.random.Random

class PieChartModule : Module("PieChart", ModuleCategory.Misc) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val chartSize by intValue("Chart Size", 140, 100..300)
    private val updateRate by intValue("Update Rate (ms)", 100, 50..1000)
    private val showPercentages by boolValue("Show Percentages", true)
    private val showLabels by boolValue("Show Labels", true)
    private val transparentBackground by boolValue("Transparent Background", true)
    private val animateTransitions by boolValue("Animate Transitions", true)
    private val highlightLargest by boolValue("Highlight Largest", true)
    private val chart3DDepth by intValue("3D Depth", 15, 5..30)
    private val chartTilt by floatValue("Chart Tilt", 0.6f, 0.3f..1.0f)
    private val borderWidth by floatValue("Border Width", 1.5f, 0.5f..3.0f)
    private val legendSpacing by intValue("Legend Spacing", 2, 0..10)
    private val legendFontSize by intValue("Legend Font Size", 11, 8..16)

    private val colorIntensity by floatValue("Color Intensity", 1.0f, 0.5f..1.5f)
    private val positionX by intValue("Position X", 20, -200..200)
    private val positionY by intValue("Position Y", 100, 50..500)

    // === SPAWNER DETECTION OPTIONS ===
    private val enableSpawnerScan by boolValue("Scan Spawners", true)
    private val spawnerScanRadius by intValue("Spawner Radius", 32, 8..64)
    private val showSpawnerText by boolValue("Show Spawner Text", true)
    private val spawnerTextSize by intValue("Spawner Text Size", 14, 10..24)
    private val spawnerColorR by intValue("Spawner Color R", 255, 0..255)
    private val spawnerColorG by intValue("Spawner Color G", 0, 0..255)
    private val spawnerColorB by intValue("Spawner Color B", 0, 0..255)

    // === BYPASS OPTIONS (DonutSMP) ===
    private val bypassMode by boolValue("Bypass Mode", false)
    private val randomizeTiming by boolValue("Randomize Timing", true)
    private val minBypassDelay by intValue("Min Delay (ms)", 50, 10..500)
    private val maxBypassDelay by intValue("Max Delay (ms)", 200, 50..1000)
    private val spoofPackets by boolValue("Spoof Packets", false)

    private var lastUpdateTime = 0L
    private val performanceData = mutableMapOf<String, Long>()
    private val frameTimeHistory = mutableListOf<Long>()
    private val maxHistorySize = 60

    // Packet counters
    private var entityPackets = 0L
    private var movementPackets = 0L
    private var soundPackets = 0L
    private var blockUpdatePackets = 0L
    private var effectPackets = 0L
    private var otherPackets = 0L

    private var lastPacketCountTime = 0L
    private val packetCountInterval = 1000L

    // Spawner tracking
    private val detectedSpawners = mutableSetOf<Vector3i>()
    private var lastSpawnerScanTime = 0L
    private val spawnerScanInterval = 5000L // Scan every 5 seconds
    private val spawnerBlockId = "minecraft:mob_spawner"

    // Bypass state
    private var bypassTick = 0L

    override fun onEnabled() {
        super.onEnabled()
        try {
            if (isSessionCreated) {
                PieChartOverlay.setOverlayEnabled(true)
                updateInitialSettings()
                startPerformanceMonitoring()
                resetCounters()
                detectedSpawners.clear()
            }
        } catch (e: Exception) {
            println("Error enabling PieChart: ${e.message}")
        }
    }

    override fun onDisabled() {
        super.onDisabled()
        PieChartOverlay.setOverlayEnabled(false)
        resetCounters()
        detectedSpawners.clear()
    }

    override fun onDisconnect(reason: String) {
        PieChartOverlay.setOverlayEnabled(false)
        resetCounters()
        detectedSpawners.clear()
    }

    private fun updateSettings() {
        PieChartOverlay.setChartSize(chartSize)
        PieChartOverlay.setShowPercentages(showPercentages)
        PieChartOverlay.setShowLabels(showLabels)
        PieChartOverlay.setTransparentBackground(transparentBackground)
        PieChartOverlay.setAnimateTransitions(animateTransitions)
        PieChartOverlay.setHighlightLargest(highlightLargest)
        PieChartOverlay.setChart3DDepth(chart3DDepth)
        PieChartOverlay.setChartTilt(chartTilt)
        PieChartOverlay.setBorderWidth(borderWidth)
        PieChartOverlay.setLegendSpacing(legendSpacing)
        PieChartOverlay.setLegendFontSize(legendFontSize)
        PieChartOverlay.setColorIntensity(colorIntensity)

        // Spawner settings
        PieChartOverlay.setShowSpawnerText(showSpawnerText)
        PieChartOverlay.setSpawnerTextSize(spawnerTextSize)
        PieChartOverlay.setSpawnerColor(
            android.graphics.Color.rgb(spawnerColorR, spawnerColorG, spawnerColorB)
        )
        PieChartOverlay.setDetectedSpawners(detectedSpawners)
    }

    private fun updateInitialSettings() {
        updateSettings()
        PieChartOverlay.setPosition(positionX, positionY)
    }

    private fun startPerformanceMonitoring() {
        scope.launch {
            while (isEnabled && isSessionCreated) {
                val currentTime = System.currentTimeMillis()

                // Bypass: randomize update timing
                val effectiveUpdateRate = if (bypassMode && randomizeTiming) {
                    (updateRate * (Random.nextFloat() * 0.4f + 0.8f)).toInt()
                } else {
                    updateRate
                }

                if (currentTime - lastUpdateTime >= effectiveUpdateRate) {
                    updatePerformanceData()
                    lastUpdateTime = currentTime
                }

                delay(effectiveUpdateRate.toLong())
            }
        }
    }

    private fun updatePerformanceData() {
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastPacketCountTime >= packetCountInterval) {
            val timeElapsed = (currentTime - lastPacketCountTime).toDouble() / 1000.0

            val entityPPS = (entityPackets / timeElapsed).toLong()
            val movementPPS = (movementPackets / timeElapsed).toLong()
            val soundPPS = (soundPackets / timeElapsed).toLong()
            val blockUpdatePPS = (blockUpdatePackets / timeElapsed).toLong()
            val effectPPS = (effectPackets / timeElapsed).toLong()
            val otherPPS = (otherPackets / timeElapsed).toLong()

            performanceData["Entities"] = entityPPS * 1000
            performanceData["Movement"] = movementPPS * 800
            performanceData["Sound"] = soundPPS * 500
            performanceData["Block Updates"] = blockUpdatePPS * 1200
            performanceData["Effects"] = effectPPS * 600
            performanceData["Network"] = otherPPS * 400

            performanceData["Rendering"] = calculateRenderingTime()
            performanceData["World Tick"] = calculateWorldTickTime()
            performanceData["Unspecified"] = calculateUnspecifiedTime()

            // Add spawners to performance data if any detected
            if (detectedSpawners.isNotEmpty()) {
                performanceData["Spawners"] = detectedSpawners.size * 100L
            } else {
                performanceData.remove("Spawners")
            }

            PieChartOverlay.setPerformanceData(performanceData.toMap())

            resetPacketCounters()
            lastPacketCountTime = currentTime
        }

        updateSettings()
    }

    private fun calculateRenderingTime(): Long {
        val entityCount = session.level.entityMap.size
        val baseRenderTime = 8000L + if (bypassMode) (Math.random() * 4000).toLong() else (Math.random() * 2000).toLong()
        val entityRenderTime = entityCount * 150L
        return baseRenderTime + entityRenderTime
    }

    private fun calculateWorldTickTime(): Long {
        val entityCount = session.level.entityMap.size
        val baseTickTime = 3000L + if (bypassMode) (Math.random() * 2000).toLong() else (Math.random() * 1000).toLong()
        val entityTickTime = entityCount * 80L
        return baseTickTime + entityTickTime
    }

    private fun calculateUnspecifiedTime(): Long {
        val targetFrameTime = 16666L
        val variance = if (bypassMode) (Math.random() * 5000).toLong() else (Math.random() * 3000).toLong()
        return maxOf(2000L, (targetFrameTime * 0.4).toLong() + variance)
    }

    private fun resetCounters() {
        performanceData.clear()
        frameTimeHistory.clear()
        resetPacketCounters()
        lastPacketCountTime = System.currentTimeMillis()
    }

    private fun resetPacketCounters() {
        entityPackets = 0L
        movementPackets = 0L
        soundPackets = 0L
        blockUpdatePackets = 0L
        effectPackets = 0L
        otherPackets = 0L
    }

    private fun getRandomBypassDelay(): Long {
        if (!bypassMode || !randomizeTiming) return 0L
        return Random.nextLong(minBypassDelay.toLong(), maxBypassDelay.toLong())
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled || !isSessionCreated) return

        val startTime = System.nanoTime()
        val now = System.currentTimeMillis()

        // Process packet for performance tracking
        when (val packet = interceptablePacket.packet) {
            is AddEntityPacket, is AddPlayerPacket, is AddItemEntityPacket,
            is RemoveEntityPacket, is SetEntityDataPacket -> {
                entityPackets++
            }
            is MoveEntityAbsolutePacket, is MoveEntityDeltaPacket, is PlayerAuthInputPacket -> {
                movementPackets++
            }
            is LevelSoundEventPacket -> {
                soundPackets++
            }
            is UpdateBlockPacket -> {
                blockUpdatePackets++

                // === SPAWNER DETECTION ===
                if (enableSpawnerScan) {
                    detectSpawnerFromBlockUpdate(packet)
                }
            }
            is BlockEntityDataPacket -> {
                // === SILENT SPAWNER DETECTION VIA BLOCK ENTITY DATA ===
                if (enableSpawnerScan) {
                    detectSpawnerFromBlockEntity(packet)
                }
            }
            is MobEffectPacket -> {
                effectPackets++
            }
            else -> {
                otherPackets++
            }
        }

        // === PERIODIC SPAWNER SCAN ===
        if (enableSpawnerScan && isSessionCreated &&
            now - lastSpawnerScanTime >= spawnerScanInterval) {

            val bypassDelay = getRandomBypassDelay()
            if (bypassDelay > 0) {
                scope.launch {
                    delay(bypassDelay)
                    scanNearbySpawners()
                }
            } else {
                scanNearbySpawners()
            }
            lastSpawnerScanTime = now
        }

        val processingTime = System.nanoTime() - startTime

        // Bypass: add random noise to timing
        val adjustedTime = if (bypassMode) {
            processingTime + (Math.random() * 500).toLong()
        } else {
            processingTime
        }

        frameTimeHistory.add(adjustedTime / 1000)
        if (frameTimeHistory.size > maxHistorySize) {
            frameTimeHistory.removeAt(0)
        }

        // Bypass: spoof random packets every so often
        if (bypassMode && spoofPackets) {
            bypassTick++
            if (bypassTick % 37 == 0L) {
                otherPackets += Random.nextInt(1, 5)
            }
        }
    }

    /**
     * Resolve block identifier from UpdateBlockPacket runtime ID
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

    /**
     * Detect ANY mob spawner from UpdateBlockPacket — works for ALL types:
     * skeleton spawner, pig spawner, zombie spawner, etc.
     * All spawners in Minecraft use the 'minecraft:mob_spawner' block ID.
     */
    private fun detectSpawnerFromBlockUpdate(packet: UpdateBlockPacket) {
        try {
            val blockPos = packet.blockPosition
            val identifier = resolveBlockIdentifier(packet)?.lowercase() ?: return

            // Minecraft: ALL spawner types use block ID "minecraft:mob_spawner"
            // The entity type is stored in the block entity data, not the block ID
            val isSpawner = identifier.contains("mob_spawner") ||
                    identifier.contains("spawner") ||
                    identifier.endsWith("mob_spawner")

            if (isSpawner) {
                val pos = Vector3i.from(blockPos.x, blockPos.y, blockPos.z)
                if (detectedSpawners.add(pos)) {
                    PieChartOverlay.setDetectedSpawners(detectedSpawners)
                }
            }
        } catch (_: Exception) {
            // Ignore errors silently
        }
    }

    /**
     * Detect mob spawners from BlockEntityDataPacket NBT data.
     * This catches spawners sent as block entities when chunks load.
     * Completely silent — no chat messages are sent.
     */
    private fun detectSpawnerFromBlockEntity(packet: BlockEntityDataPacket) {
        try {
            val data = packet.data ?: return
            val id = try {
                data.getString("id")
            } catch (_: Exception) {
                data["id"] as? String
            } ?: return

            if (!id.contains("Spawner", ignoreCase = true) &&
                !id.contains("mob_spawner", ignoreCase = true)
            ) {
                return
            }

            val blockPos = packet.blockPosition
            val pos = Vector3i.from(blockPos.x, blockPos.y, blockPos.z)
            if (detectedSpawners.add(pos)) {
                PieChartOverlay.setDetectedSpawners(detectedSpawners)
            }
        } catch (_: Exception) {
            // Ignore errors silently
        }
    }

    /**
     * Scan nearby blocks for spawners (periodic check)
     */
    private fun scanNearbySpawners() {
        if (!isSessionCreated) return

        try {
            val playerPos = session.localPlayer.vec3Position
            val px = playerPos.x.toInt()
            val py = playerPos.y.toInt()
            val pz = playerPos.z.toInt()

            val radius = spawnerScanRadius

            // Check blocks in chunks around player
            // Note: We rely on UpdateBlockPacket for detection, but also
            // track spawner data from the world state
            val beforeCount = detectedSpawners.size

            // Clean up spawners that are too far away
            detectedSpawners.removeIf { pos ->
                val dx = pos.x - px
                val dy = pos.y - py
                val dz = pos.z - pz
                val dist = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble())
                dist > radius
            }

            if (detectedSpawners.size != beforeCount) {
                PieChartOverlay.setDetectedSpawners(detectedSpawners)
            }
        } catch (_: Exception) {
            // Ignore errors during scan
        }
    }
}
