package com.retrivedmods.wclient.game.module.world

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.Ability
import org.cloudburstmc.protocol.bedrock.data.AbilityLayer
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.PlayerPermission
import org.cloudburstmc.protocol.bedrock.data.command.CommandPermission
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket
import org.cloudburstmc.protocol.bedrock.packet.TextPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateAbilitiesPacket
import kotlin.collections.addAll

class FreeCameraModule : Module("free_camera", ModuleCategory.World) {

    private var originalPosition: Vector3f? = null
    private var freeCamPosition: Vector3f? = null
    private var lastSentFreeCamPosition: Vector3f? = null
    private val verticalSpeed by floatValue("verticalSpeed", 0.25f, 0.05f..2.0f)
    private val horizontalSpeed by floatValue("horizontalSpeed", 0.25f, 0.05f..2.0f)

    private val enableFlyNoClipPacket = UpdateAbilitiesPacket().apply {
        playerPermission = PlayerPermission.OPERATOR
        commandPermission = CommandPermission.OWNER
        abilityLayers.add(AbilityLayer().apply {
            layerType = AbilityLayer.Type.BASE
            abilitiesSet.addAll(Ability.entries.toTypedArray())
            abilityValues.addAll(
                arrayOf(
                    Ability.BUILD,
                    Ability.MINE,
                    Ability.DOORS_AND_SWITCHES,
                    Ability.OPEN_CONTAINERS,
                    Ability.ATTACK_PLAYERS,
                    Ability.ATTACK_MOBS,
                    Ability.MAY_FLY,
                    Ability.FLY_SPEED,
                    Ability.WALK_SPEED,
                    Ability.NO_CLIP,
                    Ability.OPERATOR_COMMANDS
                )
            )
            walkSpeed = 0.1f
            flySpeed = 0.15f
        })
    }

    private val disableFlyNoClipPacket = UpdateAbilitiesPacket().apply {
        playerPermission = PlayerPermission.OPERATOR
        commandPermission = CommandPermission.OWNER
        abilityLayers.add(AbilityLayer().apply {
            layerType = AbilityLayer.Type.BASE
            abilitiesSet.addAll(Ability.entries.toTypedArray())
            abilityValues.addAll(
                arrayOf(
                    Ability.BUILD,
                    Ability.MINE,
                    Ability.DOORS_AND_SWITCHES,
                    Ability.OPEN_CONTAINERS,
                    Ability.ATTACK_PLAYERS,
                    Ability.ATTACK_MOBS,
                    Ability.OPERATOR_COMMANDS
                )
            )
            walkSpeed = 0.1f
        })
    }

    private var isFlyNoClipEnabled = false

    @OptIn(DelicateCoroutinesApi::class)
    override fun onEnabled() {
        super.onEnabled()
        if (isSessionCreated) {
            // Store original position immediately when enabled
            originalPosition = Vector3f.from(
                session.localPlayer.posX,
                session.localPlayer.posY,
                session.localPlayer.posZ
            )
            freeCamPosition = originalPosition
            lastSentFreeCamPosition = null

            GlobalScope.launch {
                for (i in 5 downTo 1) {
                    val countdownMessage = "§l§b[WClient] §r§7FreeCam will enable in §e$i §7seconds"
                    sendCountdownMessage(countdownMessage)
                    delay(1000)
                }

                enableFlyNoClipPacket.uniqueEntityId = session.localPlayer.uniqueEntityId
                enableFlyNoClipPacket.abilityLayers.firstOrNull()?.flySpeed = horizontalSpeed
                session.clientBound(enableFlyNoClipPacket)
                isFlyNoClipEnabled = true
            }
        }
    }

    override fun onDisabled() {
        super.onDisabled()
        if (isSessionCreated && originalPosition != null) {
            // Return to original position via teleport
            val player = session.localPlayer
            val returnPacket = MovePlayerPacket().apply {
                runtimeEntityId = player.runtimeEntityId
                position = originalPosition
                rotation = Vector3f.from(player.rotationYaw, player.rotationPitch, 0f)
                mode = MovePlayerPacket.Mode.TELEPORT
                onGround = true
                ridingRuntimeEntityId = 0
                tick = player.tickExists
            }
            session.clientBound(returnPacket)

            originalPosition = null
            freeCamPosition = null
            lastSentFreeCamPosition = null

            disableFlyNoClipPacket.uniqueEntityId = session.localPlayer.uniqueEntityId
            session.clientBound(disableFlyNoClipPacket)
            isFlyNoClipEnabled = false
        }
    }

    private fun sendCountdownMessage(message: String) {
        val textPacket = TextPacket().apply {
            type = TextPacket.Type.RAW
            this.message = message
            xuid = ""
            sourceName = ""
        }

        session.clientBound(textPacket)
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        val packet = interceptablePacket.packet
        if (packet is PlayerAuthInputPacket && isEnabled) {
            if (isFlyNoClipEnabled) {
                updateFreeCamPosition(packet)
            }

            interceptablePacket.intercept()
        }
    }

    /**
     * Updates the virtual freecam position based on user input and forces the
     * client camera to that position using MovePlayerPacket. This bypasses the
     * usual Y=0 anticheat/void barrier on servers like DonutSMP by teleporting
     * the local view directly instead of relying on velocity motion.
     *
     * Movement is now relative to the camera yaw so that forward always moves
     * where the player is looking, and vertical/horizontal speeds are fully
     * configurable.
     */
    private fun updateFreeCamPosition(packet: PlayerAuthInputPacket) {
        val currentPos = freeCamPosition ?: return
        val player = session.localPlayer
        val runtimeEntityId = player.runtimeEntityId

        var verticalMotion = 0f
        var horizontalMotion = 0f
        var forwardMotion = 0f

        // Vertical movement
        if (packet.inputData.contains(PlayerAuthInputData.JUMPING)) {
            verticalMotion = verticalSpeed
        } else if (packet.inputData.contains(PlayerAuthInputData.SNEAKING)) {
            verticalMotion = -verticalSpeed
        }

        // Horizontal movement relative to camera yaw
        val rawX = packet.motion.x
        val rawZ = packet.motion.y
        val rawLen = kotlin.math.hypot(rawX, rawZ)
        if (rawLen > 0.01f) {
            val normX = rawX / rawLen // strafe (left/right)
            val normZ = rawZ / rawLen // forward/back

            // yaw in radians; use the local player's yaw so movement follows the camera
            val yawRad = Math.toRadians(player.rotationYaw.toDouble())
            val sinYaw = kotlin.math.sin(yawRad).toFloat()
            val cosYaw = kotlin.math.cos(yawRad).toFloat()

            // rotate the input vector so movement follows the camera direction
            horizontalMotion = (normX * cosYaw - normZ * sinYaw) * horizontalSpeed
            forwardMotion = (normX * sinYaw + normZ * cosYaw) * horizontalSpeed
        }

        // Compute new virtual position
        val newPos = Vector3f.from(
            currentPos.x + horizontalMotion,
            currentPos.y + verticalMotion,
            currentPos.z + forwardMotion
        )

        freeCamPosition = newPos

        // Force the client camera to the new position via MovePlayerPacket.
        // Mode TELEPORT tells the client to accept the position without smoothing,
        // which helps pass through void/Y=0 barriers on strict servers.
        val lastPos = lastSentFreeCamPosition
        val threshold = 0.005f
        val shouldSend = lastPos == null ||
                kotlin.math.abs(lastPos.x - newPos.x) > threshold ||
                kotlin.math.abs(lastPos.y - newPos.y) > threshold ||
                kotlin.math.abs(lastPos.z - newPos.z) > threshold

        if (shouldSend) {
            lastSentFreeCamPosition = newPos

            val movePacket = MovePlayerPacket().apply {
                this.runtimeEntityId = runtimeEntityId
                position = newPos
                rotation = packet.rotation
                this.mode = MovePlayerPacket.Mode.TELEPORT
                onGround = true
                ridingRuntimeEntityId = 0
                tick = player.tickExists
            }

            session.clientBound(movePacket)
        }

        // Also send a small vertical velocity to the client for smooth interpolation
        // when the server sends motion updates.
        if (verticalMotion != 0f) {
            val motionPacket = SetEntityMotionPacket().apply {
                this.runtimeEntityId = runtimeEntityId
                motion = Vector3f.from(0f, verticalMotion, 0f)
            }
            session.clientBound(motionPacket)
        }
    }
}
