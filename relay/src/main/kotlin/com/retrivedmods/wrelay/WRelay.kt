package com.retrivedmods.wrelay

import com.retrivedmods.wrelay.WRelaySession.ClientSession
import com.retrivedmods.wrelay.address.WAddress
import com.retrivedmods.wrelay.address.inetSocketAddress
import com.retrivedmods.wrelay.codec.CodecRegistry
import com.retrivedmods.wrelay.config.EnhancedServerConfig
import com.retrivedmods.wrelay.connection.ConnectionManager
import com.retrivedmods.wrelay.util.ServerCompatUtils
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioDatagramChannel
import kotlinx.coroutines.*
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption
import org.cloudburstmc.netty.handler.codec.raknet.server.RakServerRateLimiter
import org.cloudburstmc.protocol.bedrock.BedrockPeer
import org.cloudburstmc.protocol.bedrock.BedrockPong
import org.cloudburstmc.protocol.bedrock.PacketDirection
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockChannelInitializer
import kotlin.random.Random

class WRelay(
    var localAddress: WAddress = WAddress("0.0.0.0", 19132),
    val advertisement: BedrockPong = DefaultAdvertisement,
    val serverConfig: EnhancedServerConfig = EnhancedServerConfig.DEFAULT
) {

    @Suppress("MemberVisibilityCanBePrivate")
    companion object {

        val DefaultCodec: BedrockCodec
            get() = CodecRegistry.getLatestCodec()

        val DefaultAdvertisement: BedrockPong
            get() = BedrockPong()
                .edition("MCPE")
                .gameType("Survival")
                .version(DefaultCodec.minecraftVersion)
                .protocolVersion(DefaultCodec.protocolVersion)
                .motd("§cWelcome To WRelay§c")
                .playerCount(0)
                .maximumPlayerCount(20)
                .subMotd("WClient")
                .nintendoLimited(false)

    }

    @Suppress("MemberVisibilityCanBePrivate")
    val isRunning: Boolean
        get() = channelFuture != null

    private var channelFuture: ChannelFuture? = null
    private var serverEventLoopGroup: NioEventLoopGroup? = null

    var wRelaySession: WRelaySession? = null
        internal set
    internal var connectionManager: ConnectionManager? = null

    var remoteAddress: WAddress? = null
        internal set

    fun capture(
        remoteAddress: WAddress = WAddress("geo.hivebedrock.network", 19132),
        onSessionCreated: WRelaySession.() -> Unit
    ): WRelay {
        if (isRunning) {
            return this
        }

        this.remoteAddress = remoteAddress

        if (ServerCompatUtils.isProtectedServer(remoteAddress)) {
            println("Protected server detected: ${remoteAddress.hostName}")
            val tips = ServerCompatUtils.getConnectionTips(remoteAddress)
            tips.forEach { println("  - $it") }

            val serverInfo = ServerCompatUtils.extractServerInfo(remoteAddress.hostName)
            if (serverInfo != null) {
                println("  - Server ID: ${serverInfo.serverId}")
                println("  - Domain: ${serverInfo.domain}")
            }
        }

        serverEventLoopGroup = NioEventLoopGroup()

        var boundFuture: io.netty.channel.ChannelFuture? = null
        val basePort = localAddress.port
        val maxPort = basePort + 10
        var attemptPort = basePort

        while (attemptPort <= maxPort && boundFuture == null) {
            try {
                advertisement
                    .ipv4Port(attemptPort)
                    .ipv6Port(attemptPort)

                val future = ServerBootstrap()
                    .group(serverEventLoopGroup)
                    .channelFactory(RakChannelFactory.server(NioDatagramChannel::class.java))
                    .option(RakChannelOption.RAK_ADVERTISEMENT, advertisement.toByteBuf())
                    .option(RakChannelOption.RAK_GUID, Random.nextLong())
                    .childHandler(object : BedrockChannelInitializer<WRelaySession.ServerSession>() {

                        override fun createSession0(peer: BedrockPeer, subClientId: Int): WRelaySession.ServerSession {
                            println("WRelay: client connecting, creating server session")
                            return WRelaySession(peer, subClientId, this@WRelay)
                                .also {
                                    wRelaySession = it
                                    val config = if (remoteAddress != null && ServerCompatUtils.isProtectedServer(remoteAddress!!)) {
                                        ServerCompatUtils.getRecommendedConfig(remoteAddress!!)
                                    } else {
                                        serverConfig
                                    }
                                    connectionManager = ConnectionManager(it, config)
                                    it.onSessionCreated()
                                }
                                .server
                        }

                        override fun initSession(session: WRelaySession.ServerSession) {
                            println("WRelay: server session initialized")
                        }

                        override fun preInitChannel(channel: Channel) {
                            println("WRelay: preInitChannel for incoming client")
                            channel.attr(PacketDirection.ATTRIBUTE).set(PacketDirection.CLIENT_BOUND)
                            super.preInitChannel(channel)
                        }

                    })
                    .localAddress(java.net.InetSocketAddress("0.0.0.0", attemptPort))
                    .bind()
                    .awaitUninterruptibly()

                if (future.isSuccess) {
                    boundFuture = future
                } else {
                    println("WRelay: failed to bind port $attemptPort, trying next...")
                    attemptPort++
                }
            } catch (e: Exception) {
                println("WRelay: exception binding port $attemptPort: ${e.message}")
                e.printStackTrace()
                attemptPort++
            }
        }

        if (boundFuture == null) {
            throw RuntimeException("Failed to bind WRelay to any port in range $basePort-$maxPort")
        }

        localAddress = WAddress(localAddress.hostName, attemptPort)

        try {
            boundFuture.channel().pipeline().remove(RakServerRateLimiter.NAME)
        } catch (e: Exception) {
            println("RakServerRateLimiter not present or could not be removed: ${e.message}")
        }
        channelFuture = boundFuture
        println("WRelay server bound to ${localAddress.hostName}:${localAddress.port}")

        return this
    }

    internal fun connectToServer(onSessionCreated: ClientSession.() -> Unit) {
        val manager = connectionManager ?: throw IllegalStateException("Connection manager not initialized")
        val address = remoteAddress ?: throw IllegalStateException("Remote address not set")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = manager.connectToServer(address, onSessionCreated)
                if (result.isFailure) {
                    println("Failed to connect to server: ${result.exceptionOrNull()?.message}")
                    result.exceptionOrNull()?.printStackTrace()
                    wRelaySession?.server?.disconnect("Failed to connect to server: ${result.exceptionOrNull()?.message}")
                    wRelaySession?.listeners?.forEach { listener ->
                        runCatching {
                            listener.onDisconnect("Connection failed: ${result.exceptionOrNull()?.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                println("Error during connection: ${e.message}")
                e.printStackTrace()
                wRelaySession?.server?.disconnect("Connection error: ${e.message}")
            }
        }
    }

    suspend fun connectToServerAsync(onSessionCreated: ClientSession.() -> Unit): Result<ClientSession> {
        val manager = connectionManager ?: return Result.failure(IllegalStateException("Connection manager not initialized"))
        val address = remoteAddress ?: return Result.failure(IllegalStateException("Remote address not set"))

        return manager.connectToServer(address, onSessionCreated)
    }

    fun stop() {
        try {
            connectionManager?.cleanup()
            wRelaySession?.client?.disconnect()
            wRelaySession?.server?.disconnect()
            channelFuture?.channel()?.close()?.sync()
            channelFuture = null
            wRelaySession = null
            connectionManager = null
        } catch (e: Exception) {
            println("Error stopping WRelay: ${e.message}")
        } finally {
            try {
                serverEventLoopGroup?.shutdownGracefully()?.sync()
            } catch (e: Exception) {
                println("Error shutting down event loop group: ${e.message}")
            }
            serverEventLoopGroup = null
        }
    }
}
