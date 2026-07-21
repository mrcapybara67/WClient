package com.retrivedmods.wclient.overlay.hud

import android.graphics.Color as AndroidColor
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import com.retrivedmods.wclient.game.module.visual.ChunkFinderModule
import com.retrivedmods.wclient.overlay.OverlayManager
import com.retrivedmods.wclient.overlay.OverlayWindow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class ChunkFinderOverlay : OverlayWindow() {

    private val _layoutParams by lazy {
        super.layoutParams.apply {
            flags = flags or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
    }

    override val layoutParams: WindowManager.LayoutParams
        get() = _layoutParams

    data class BlockOverlayData(
        val blockX: Int,
        val blockY: Int,
        val blockZ: Int,
        val relativeX: Float,
        val relativeZ: Float,
        val distance: Float,
        val blockType: String,
        val blockName: String = "",
        val discoveredTime: Long,
        val alpha: Float
    )

    private var trackedBlocks by mutableStateOf<List<BlockOverlayData>>(emptyList())
    private var overlayMode by mutableStateOf(ChunkFinderModule.OverlayMode.THIN_BOX)
    private var boxThickness by mutableStateOf(1.8f)
    private var overlayOpacity by mutableStateOf(0.85f)
    private var maxRenderDistance by mutableStateOf(64)
    private var fadeDistance by mutableStateOf(48)
    private var showCoordinates by mutableStateOf(true)
    private var showBlockName by mutableStateOf(true)
    private var glowEffect by mutableStateOf(true)
    private var smoothFade by mutableStateOf(true)
    private var chunkBorderLines by mutableStateOf(false)

    // Block type colors
    private var cobbledDeepslateColor = AndroidColor.rgb(80, 78, 90)
    private var endStoneColor = AndroidColor.rgb(255, 255, 190)
    private var deepslateColor = AndroidColor.rgb(65, 60, 72)
    private var otherOreColor = AndroidColor.rgb(255, 185, 0)

    // Camera state
    private var cameraYaw = 0f
    private var cameraPitch = 0f
    private var playerX = 0f
    private var playerZ = 0f
    private var playerY = 0f

    companion object {
        val overlayInstance by lazy { ChunkFinderOverlay() }
        private var shouldShowOverlay = false

        fun setOverlayEnabled(enabled: Boolean) {
            shouldShowOverlay = enabled
            try {
                if (enabled) OverlayManager.showOverlayWindow(overlayInstance)
                else OverlayManager.dismissOverlayWindow(overlayInstance)
            } catch (_: Exception) {}
        }

        fun isOverlayEnabled(): Boolean = shouldShowOverlay

        fun setTrackedBlocks(blocks: List<BlockOverlayData>) {
            overlayInstance.trackedBlocks = blocks
        }

        fun setOverlayMode(mode: ChunkFinderModule.OverlayMode) {
            overlayInstance.overlayMode = mode
        }

        fun setBoxThickness(thickness: Float) {
            overlayInstance.boxThickness = thickness
        }

        fun setOverlayOpacity(opacity: Float) {
            overlayInstance.overlayOpacity = opacity
        }

        fun setMaxRenderDistance(distance: Int) {
            overlayInstance.maxRenderDistance = distance
        }

        fun setFadeDistance(distance: Int) {
            overlayInstance.fadeDistance = distance
        }

        fun setShowCoordinates(show: Boolean) {
            overlayInstance.showCoordinates = show
        }

        fun setShowBlockName(show: Boolean) {
            overlayInstance.showBlockName = show
        }

        fun setGlowEffect(glow: Boolean) {
            overlayInstance.glowEffect = glow
        }

        fun setSmoothFade(fade: Boolean) {
            overlayInstance.smoothFade = fade
        }

        fun setChunkBorderLines(show: Boolean) {
            overlayInstance.chunkBorderLines = show
        }

        fun setBlockTypeColors(
            cdR: Int, cdG: Int, cdB: Int,
            esR: Int, esG: Int, esB: Int,
            dsR: Int, dsG: Int, dsB: Int,
            otherR: Int, otherG: Int, otherB: Int
        ) {
            overlayInstance.cobbledDeepslateColor = AndroidColor.rgb(cdR, cdG, cdB)
            overlayInstance.endStoneColor = AndroidColor.rgb(esR, esG, esB)
            overlayInstance.deepslateColor = AndroidColor.rgb(dsR, dsG, dsB)
            overlayInstance.otherOreColor = AndroidColor.rgb(otherR, otherG, otherB)
        }

        fun setCameraState(yaw: Float, pitch: Float, x: Float, y: Float, z: Float) {
            overlayInstance.cameraYaw = yaw
            overlayInstance.cameraPitch = pitch
            overlayInstance.playerX = x
            overlayInstance.playerY = y
            overlayInstance.playerZ = z
        }
    }

    @Composable
    override fun Content() {
        if (!shouldShowOverlay || trackedBlocks.isEmpty()) return

        Canvas(modifier = Modifier.fillMaxSize()) {
            drawBlockOverlays()
        }
    }

    /**
     * Main rendering: project 3D world positions to 2D screen using player yaw
     * and draw thin boxes like Glazed / Krypton Client
     */
    private fun DrawScope.drawBlockOverlays() {
        val centerX = size.width / 2f
        val centerY = size.height / 2f

        // Convert yaw/pitch to radians
        val yawRad = Math.toRadians(cameraYaw.toDouble()).toFloat()
        val pitchRad = Math.toRadians(cameraPitch.toDouble()).toFloat()

        // Scale: how many pixels per block at distance 1
        val baseScale = size.width / (maxRenderDistance * 2.2f)

        // Draw chunk borders if enabled
        if (chunkBorderLines) {
            drawChunkBorders(centerX, centerY, yawRad, baseScale)
        }

        trackedBlocks.forEach { block ->
            if (block.distance > maxRenderDistance) return@forEach
            val alpha = (block.alpha * overlayOpacity).coerceIn(0.05f, 1f)

            val dx = block.relativeX
            val dz = block.relativeZ

            // World angle to camera angle
            val blockAngle = atan2(dz, dx)
            val relativeAngle = blockAngle - yawRad
            val cosRel = cos(relativeAngle)

            // Cull blocks behind the player
            if (cosRel < -0.15f) return@forEach

            // Project to screen: distance-based forward projection
            val forwardDist = dx * cos(yawRad) - dz * sin(yawRad)
            val sideDist = dx * sin(yawRad) + dz * cos(yawRad)

            // Screen X: side projection
            val screenX = centerX + sideDist * baseScale

            // Distance-based scale: far blocks get smaller
            val distanceFactor = (30f / (forwardDist.coerceAtLeast(1f) * 0.4f + 1f)).coerceIn(3f, 28f)

            // Screen Y: vertical offset based on difference from player Y
            val dy = (block.blockY - playerY).coerceIn(-20f, 20f)
            val screenY = centerY - dy * baseScale * 0.3f

            val color = getBlockTypeColor(block.blockType, alpha)

            // Draw thin box overlay (Glazed/Krypton style)
            drawBlockOverlay(
                screenX.coerceIn(0f, size.width),
                screenY.coerceIn(0f, size.height),
                distanceFactor, color, block
            )
        }
    }

    /**
     * Draw a thin box with glow — matching Glazed / premium client style
     */
    private fun DrawScope.drawBlockOverlay(
        screenX: Float,
        screenY: Float,
        boxSize: Float,
        color: Color,
        block: BlockOverlayData
    ) {
        val halfSize = boxSize / 2f
        val cornerRadius = 2f

        when (overlayMode) {
            ChunkFinderModule.OverlayMode.THIN_BOX -> {
                val strokeWidth = boxThickness

                // === OUTER GLOW (like Glazed/Krypton) ===
                if (glowEffect) {
                    // Large outer glow
                    val glowColor = color.copy(alpha = color.alpha * 0.12f)
                    drawRoundRect(
                        color = glowColor,
                        topLeft = Offset(screenX - halfSize - 6f, screenY - halfSize - 6f),
                        size = androidx.compose.ui.geometry.Size(boxSize + 12f, boxSize + 12f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius + 3f),
                        style = Stroke(width = strokeWidth * 3f, cap = StrokeCap.Round)
                    )

                    // Medium glow (soft aura)
                    val midGlow = color.copy(alpha = color.alpha * 0.25f)
                    drawRoundRect(
                        color = midGlow,
                        topLeft = Offset(screenX - halfSize - 3f, screenY - halfSize - 3f),
                        size = androidx.compose.ui.geometry.Size(boxSize + 6f, boxSize + 6f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius + 1f),
                        style = Stroke(width = strokeWidth * 1.8f, cap = StrokeCap.Round)
                    )
                }

                // === MAIN THIN BOX OUTLINE ===
                drawRoundRect(
                    color = color,
                    topLeft = Offset(screenX - halfSize, screenY - halfSize),
                    size = androidx.compose.ui.geometry.Size(boxSize, boxSize),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius),
                    style = Stroke(
                        width = strokeWidth,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )

                // === INNER GLOW HIGHLIGHT ===
                if (glowEffect) {
                    val innerGlow = color.copy(alpha = color.alpha * 0.15f)
                    drawRoundRect(
                        color = innerGlow,
                        topLeft = Offset(screenX - halfSize + 1.5f, screenY - halfSize + 1.5f),
                        size = androidx.compose.ui.geometry.Size(boxSize - 3f, boxSize - 3f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius),
                        style = Stroke(width = strokeWidth * 0.4f)
                    )
                }
            }

            ChunkFinderModule.OverlayMode.FILLED_BOX -> {
                // Filled with slight transparency + border
                drawRoundRect(
                    color = color.copy(alpha = color.alpha * 0.2f),
                    topLeft = Offset(screenX - halfSize, screenY - halfSize),
                    size = androidx.compose.ui.geometry.Size(boxSize, boxSize),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius),
                    style = Fill
                )
                drawRoundRect(
                    color = color,
                    topLeft = Offset(screenX - halfSize, screenY - halfSize),
                    size = androidx.compose.ui.geometry.Size(boxSize, boxSize),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius),
                    style = Stroke(width = boxThickness)
                )
            }

            ChunkFinderModule.OverlayMode.CORNER_BOX -> {
                val cornerLen = boxSize / 3f
                val stroke = boxThickness
                // Top-left corner
                drawLine(color, Offset(screenX - halfSize, screenY - halfSize), Offset(screenX - halfSize + cornerLen, screenY - halfSize), stroke, StrokeCap.Round)
                drawLine(color, Offset(screenX - halfSize, screenY - halfSize), Offset(screenX - halfSize, screenY - halfSize + cornerLen), stroke, StrokeCap.Round)
                // Top-right corner
                drawLine(color, Offset(screenX + halfSize, screenY - halfSize), Offset(screenX + halfSize - cornerLen, screenY - halfSize), stroke, StrokeCap.Round)
                drawLine(color, Offset(screenX + halfSize, screenY - halfSize), Offset(screenX + halfSize, screenY - halfSize + cornerLen), stroke, StrokeCap.Round)
                // Bottom-left corner
                drawLine(color, Offset(screenX - halfSize, screenY + halfSize), Offset(screenX - halfSize + cornerLen, screenY + halfSize), stroke, StrokeCap.Round)
                drawLine(color, Offset(screenX - halfSize, screenY + halfSize), Offset(screenX - halfSize, screenY + halfSize - cornerLen), stroke, StrokeCap.Round)
                // Bottom-right corner
                drawLine(color, Offset(screenX + halfSize, screenY + halfSize), Offset(screenX + halfSize - cornerLen, screenY + halfSize), stroke, StrokeCap.Round)
                drawLine(color, Offset(screenX + halfSize, screenY + halfSize), Offset(screenX + halfSize, screenY + halfSize - cornerLen), stroke, StrokeCap.Round)
            }

            ChunkFinderModule.OverlayMode.OUTLINE_ONLY -> {
                val path = Path().apply {
                    moveTo(screenX - halfSize, screenY - halfSize)
                    lineTo(screenX + halfSize, screenY - halfSize)
                    lineTo(screenX + halfSize, screenY + halfSize)
                    lineTo(screenX - halfSize, screenY + halfSize)
                    close()
                }
                drawPath(path, color, style = Stroke(width = boxThickness, join = StrokeJoin.Round))
            }
        }

        // === TEXT: Block name + coordinates below the box ===
        if (showBlockName || showCoordinates) {
            val textPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                isFakeBoldText = true
            }

            val textY = screenY + halfSize + 6f
            val blockName = block.blockName.ifEmpty {
                block.blockType.replace("_", " ")
                    .split(" ")
                    .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            }

        drawIntoCanvas { canvas ->
            if (showBlockName) {
                textPaint.textSize = 26f
                textPaint.textAlign = android.graphics.Paint.Align.CENTER
                textPaint.setShadowLayer(3f, 1.5f, 1.5f, AndroidColor.BLACK)
                textPaint.color = AndroidColor.WHITE
                canvas.nativeCanvas.drawText(blockName, screenX, textY, textPaint)
            }

            if (showCoordinates) {
                textPaint.textSize = 20f
                textPaint.textAlign = android.graphics.Paint.Align.CENTER
                textPaint.setShadowLayer(2f, 1f, 1f, AndroidColor.BLACK)
                val blockColor = getBlockTypeAndroidColor(block.blockType)
                textPaint.color = AndroidColor.argb(
                    (255 * color.alpha).toInt(),
                    AndroidColor.red(blockColor),
                    AndroidColor.green(blockColor),
                    AndroidColor.blue(blockColor)
                )
                val coordText = "${block.blockX} ${block.blockY} ${block.blockZ}"
                canvas.nativeCanvas.drawText(coordText, screenX, textY + 22f, textPaint)
            }
        }
        }
    }

    /**
     * Draw chunk border dots in a 2D radar-style projection
     */
    private fun DrawScope.drawChunkBorders(centerX: Float, centerY: Float, yawRad: Float, scale: Float) {
        val borderColor = Color(0.4f, 0.4f, 0.4f, 0.12f)
        val chunkSize = 16
        val playerChunkX = (playerX / chunkSize).toInt()
        val playerChunkZ = (playerZ / chunkSize).toInt()
        val renderChunks = (maxRenderDistance / chunkSize) + 2

        for (cx in -renderChunks..renderChunks) {
            for (cz in -renderChunks..renderChunks) {
                if (cx == 0 && cz == 0) continue // Skip player chunk center
                val worldX = (playerChunkX + cx) * chunkSize
                val worldZ = (playerChunkZ + cz) * chunkSize
                val dx = worldX - playerX
                val dz = worldZ - playerZ
                val screenX = centerX + (dx * cos(yawRad) - dz * sin(yawRad)) * scale
                val screenZ = centerY + (dx * sin(yawRad) + dz * cos(yawRad)) * scale
                if (screenX in -50f..size.width + 50f && screenZ in -50f..size.height + 50f) {
                    drawCircle(borderColor, 2f, Offset(screenX, screenZ))
                }
            }
        }
    }

    private fun getBlockTypeColor(type: String, alpha: Float): Color {
        val androidColor = getBlockTypeAndroidColor(type)
        return Color(
            red = AndroidColor.red(androidColor) / 255f,
            green = AndroidColor.green(androidColor) / 255f,
            blue = AndroidColor.blue(androidColor) / 255f,
            alpha = alpha
        )
    }

    private fun getBlockTypeAndroidColor(type: String): Int {
        return when (type) {
            "COBBLED_DEEPSLATE" -> cobbledDeepslateColor
            "END_STONE" -> endStoneColor
            "DEEPSLATE" -> deepslateColor
            "OTHER_ORE" -> otherOreColor
            else -> AndroidColor.WHITE
        }
    }
}
