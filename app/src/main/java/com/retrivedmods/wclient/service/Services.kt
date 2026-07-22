package com.retrivedmods.wclient.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.graphics.PixelFormat
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.retrivedmods.wclient.game.AccountManager
import com.retrivedmods.wclient.game.GameSession
import com.retrivedmods.wclient.game.ModuleManager
import com.retrivedmods.wclient.game.module.visual.ESPModule
import com.retrivedmods.wclient.application.AppContext
import com.retrivedmods.wclient.model.CaptureModeModel
import com.retrivedmods.wclient.overlay.OverlayManager
import com.retrivedmods.wclient.render.RenderOverlayView
import com.retrivedmods.wrelay.WRelay
import com.retrivedmods.wrelay.WRelaySession
import com.retrivedmods.wrelay.address.WAddress
import com.retrivedmods.wrelay.config.EnhancedServerConfig
import com.retrivedmods.wrelay.definition.Definitions
import com.retrivedmods.wrelay.listener.AutoCodecPacketListener
import com.retrivedmods.wrelay.listener.GamingPacketHandler
import com.retrivedmods.wrelay.listener.OnlineLoginPacketListener
import com.retrivedmods.wclient.util.ServerCompatUtils
import java.io.File
import kotlin.concurrent.thread

@Suppress("MemberVisibilityCanBePrivate")
object Services {

    private val handler = Handler(Looper.getMainLooper())

    private var wRelay: WRelay? = null
    private var thread: Thread? = null

    private var renderView: RenderOverlayView? = null
    private var windowManager: WindowManager? = null

    var isActive by mutableStateOf(false)
    var detectedProtocolVersion by mutableStateOf<Int?>(null)
    var detectedMinecraftVersion by mutableStateOf<String?>(null)
    var relayPort by mutableStateOf(19132)
    var relayHost by mutableStateOf("0.0.0.0")
    var loopbackReachable by mutableStateOf<Boolean?>(null)

    fun toggle(context: Context, captureModeModel: CaptureModeModel) {
        if (!isActive) {
            on(context, captureModeModel)
            return
        }

        off()
    }

    private fun on(context: Context, captureModeModel: CaptureModeModel) {
        if (thread != null) {
            return
        }

        wRelay?.let { relay ->
            try {
                if (relay.javaClass.methods.any { it.name == "stop" }) {
                    relay.javaClass.getMethod("stop").invoke(relay)
                }
            } catch (e: Exception) {
                Log.e("Services", "Error stopping existing WRelay: ${e.message}")
            }
        }
        wRelay = null

        File(context.cacheDir, "token_cache.json")

        isActive = true
        handler.post {
            OverlayManager.show(context)
        }

        setupOverlay(context)

        // Stop any previous service instance before starting a fresh one.
        try {
            context.stopService(Intent(context, RelayService::class.java))
        } catch (e: Exception) {
            Log.e("Services", "Failed to stop previous RelayService: ${e.message}")
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(Intent(context, RelayService::class.java))
            } else {
                context.startService(Intent(context, RelayService::class.java))
            }
            Log.d("Services", "RelayService started")
        } catch (e: Exception) {
            Log.e("Services", "Failed to start RelayService: ${e.message}")
        }

        thread = thread(
            name = "WRelayThread",
            priority = Thread.MAX_PRIORITY
        ) {
            runCatching {
                ModuleManager.loadConfig()
            }.exceptionOrNull()?.let {
                it.printStackTrace()
                context.toast("Load configuration error: ${it.message}")
            }

            runCatching {
                Definitions.loadBlockPalette()
            }.exceptionOrNull()?.let {
                it.printStackTrace()
                context.toast("Load block palette error: ${it.message}")
            }

            val selectedAccount = AccountManager.selectedAccount

            runCatching {
                val remoteAddress = WAddress(
                    captureModeModel.serverHostName,
                    captureModeModel.serverPort
                )

                // Always bind to 0.0.0.0 so the relay accepts both loopback (127.0.0.1)
                // and LAN traffic. Some Android skins (Vivo/OPPO) route 127.0.0.1 to a
                // socket only when it is bound to the wildcard address.
                val bindAddress = WAddress("0.0.0.0", 19132)

                val serverConfig = getServerConfig(captureModeModel)
                // Always use WRelay directly. It handles both protected and non-protected
                // servers and is more reliable than the legacy captureGamePacket helper.
                wRelay = WRelay(
                    localAddress = bindAddress,
                    serverConfig = serverConfig
                ).capture(remoteAddress = remoteAddress) {
                    initModules(this)
                    listeners.add(AutoCodecPacketListener(this))
                    selectedAccount?.let { OnlineLoginPacketListener(this, it) }
                        ?.let { listeners.add(it) }
                    listeners.add(GamingPacketHandler(this))
                }

                val boundPort = wRelay?.localAddress?.port ?: 19132
                val boundHost = wRelay?.localAddress?.hostName ?: "0.0.0.0"
                Log.d("Services", "WRelay bound to $boundHost:$boundPort")

                val loopbackOk = testLocalUdp(boundPort)
                Log.d("Services", "Local UDP 127.0.0.1 reachable: $loopbackOk")

                handler.post {
                    relayPort = boundPort
                    relayHost = boundHost
                    loopbackReachable = loopbackOk
                    if (!loopbackOk) {
                        context.toast("Loopback UDP blocked, use the LAN IP shown")
                    } else if (boundPort != 19132) {
                        context.toast("Relay using fallback port $boundPort")
                    }
                }
            }.exceptionOrNull()?.let {
                it.printStackTrace()
                context.toast("Start WRelay error: ${it.message}")
            }
        }
    }

    private fun off() {
        thread(name = "WRelayThread") {
            ModuleManager.saveConfig()

            wRelay?.let { relay ->
                try {
                    relay.wRelaySession?.client?.disconnect()
                    relay.wRelaySession?.server?.disconnect()

                    if (relay.javaClass.methods.any { it.name == "stop" }) {
                        relay.javaClass.getMethod("stop").invoke(relay)
                        Log.d("Services", "WRelay connection stopped successfully")
                    }
                } catch (e: Exception) {
                    Log.e("Services", "Error stopping WRelay: ${e.message}")
                    e.printStackTrace()
                }
            }
            wRelay = null
            relayPort = 19132
            relayHost = "0.0.0.0"
            loopbackReachable = null

            try {
                AppContext.instance.stopService(Intent(AppContext.instance, RelayService::class.java))
                Log.d("Services", "RelayService stopped")
            } catch (e: Exception) {
                Log.e("Services", "Failed to stop RelayService: ${e.message}")
            }

            try {
                Thread.sleep(500)
            } catch (e: Exception) {
                Log.e("Services", "Error during cleanup delay: ${e.message}")
            }

            handler.post {
                OverlayManager.dismiss()
            }
            removeOverlay()
            isActive = false
            thread?.interrupt()
            thread = null

            Log.d("Services", "WRelay service stopped and cleaned up")
        }
    }

    private fun Context.toast(message: String) {
        handler.post {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun initModules(wRelaySession: WRelaySession) {
        val session = GameSession(wRelaySession)
        wRelaySession.listeners.add(session)

        wRelaySession.listeners.add(com.retrivedmods.wrelay.listener.VersionTrackingListener() { protocol, version ->
            detectedProtocolVersion = protocol
            detectedMinecraftVersion = version
            Log.i("Services", "Client version: Minecraft $version (Protocol $protocol)")
        })

        for (module in ModuleManager.modules) {
            module.session = session
        }
        Log.e("Services", "Init session")
    }

    private fun setupOverlay(context: Context) {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = PixelFormat.TRANSLUCENT

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alpha = 0.8f
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                setFitInsetsTypes(0)
                setFitInsetsSides(0)
            }
        }

        renderView = RenderOverlayView(context)
        ESPModule.setRenderView(renderView!!)



        handler.post {
            try {
                windowManager?.addView(renderView, params)
            } catch (e: Exception) {
                e.printStackTrace()
                context.toast("Failed to add overlay view: ${e.message}")
            }
        }
    }

    private fun removeOverlay() {
        renderView?.let { view ->
            windowManager?.removeView(view)
            renderView = null
        }
    }

    private fun getServerConfig(captureModeModel: CaptureModeModel): EnhancedServerConfig {
        return when (captureModeModel.serverConfigType) {
            ServerCompatUtils.ServerConfigType.FAST -> EnhancedServerConfig.FAST
            ServerCompatUtils.ServerConfigType.DEFAULT -> EnhancedServerConfig.DEFAULT
            ServerCompatUtils.ServerConfigType.AGGRESSIVE -> EnhancedServerConfig.AGGRESSIVE
            ServerCompatUtils.ServerConfigType.STANDARD -> EnhancedServerConfig.DEFAULT
        }
    }

    /**
     * Verify whether the local loopback UDP socket is reachable from this app.
     * Returns true if we can send a tiny UDP packet to 127.0.0.1 and read a reply.
     */
    private fun testLocalUdp(port: Int): Boolean {
        return try {
            java.net.DatagramSocket().use { socket ->
                socket.soTimeout = 1500
                // Unconnected Ping first byte is 0x01 in RakNet
                val data = byteArrayOf(0x01)
                val packet = java.net.DatagramPacket(
                    data,
                    data.size,
                    java.net.InetAddress.getByName("127.0.0.1"),
                    port
                )
                socket.send(packet)
                val buffer = ByteArray(256)
                val receive = java.net.DatagramPacket(buffer, buffer.size)
                socket.receive(receive)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}