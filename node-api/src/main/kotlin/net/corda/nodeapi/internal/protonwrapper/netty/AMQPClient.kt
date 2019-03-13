package net.corda.nodeapi.internal.protonwrapper.netty

import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelInitializer
import io.netty.channel.EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import io.netty.handler.proxy.HttpProxyHandler
import io.netty.handler.proxy.Socks4ProxyHandler
import io.netty.handler.proxy.Socks5ProxyHandler
import io.netty.resolver.NoopAddressResolverGroup
import io.netty.util.internal.logging.InternalLoggerFactory
import io.netty.util.internal.logging.Slf4JLoggerFactory
import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.core.utilities.contextLogger
import net.corda.nodeapi.internal.protonwrapper.messages.ReceivedMessage
import net.corda.nodeapi.internal.protonwrapper.messages.SendableMessage
import net.corda.nodeapi.internal.protonwrapper.messages.impl.SendableMessageImpl
import net.corda.nodeapi.internal.protonwrapper.netty.AMQPChannelHandler.Companion.PROXY_LOGGER_NAME
import net.corda.nodeapi.internal.requireMessageSize
import rx.Observable
import rx.subjects.PublishSubject
import java.lang.Long.min
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.withLock

enum class ProxyVersion {
    SOCKS4,
    SOCKS5,
    HTTP
}

data class ProxyConfig(val version: ProxyVersion, val proxyAddress: NetworkHostAndPort, val userName: String? = null, val password: String? = null, val proxyTimeoutMS: Long? = null) {
    init {
        if (version == ProxyVersion.SOCKS4) {
            require(password == null) { "SOCKS4 does not support a password" }
        }
    }
}

/**
 * The AMQPClient creates a connection initiator that will try to connect in a round-robin fashion
 * to the first open SSL socket. It will keep retrying until it is stopped.
 * To allow thread resource control it can accept a shared thread pool as constructor input,
 * otherwise it creates a self-contained Netty thraed pool and socket objects.
 * Once connected it can accept application packets to send via the AMQP protocol.
 */
class AMQPClient(val targets: List<NetworkHostAndPort>,
                 val allowedRemoteLegalNames: Set<CordaX500Name>,
                 private val configuration: AMQPConfiguration,
                 private val sharedThreadPool: EventLoopGroup? = null) : AutoCloseable {
    companion object {
        init {
            InternalLoggerFactory.setDefaultFactory(Slf4JLoggerFactory.INSTANCE)
        }

        val log = contextLogger()
        const val MIN_RETRY_INTERVAL = 1000L
        const val MAX_RETRY_INTERVAL = 60000L
        const val BACKOFF_MULTIPLIER = 2L
        const val NUM_CLIENT_THREADS = 2
    }

    private val lock = ReentrantLock()
    @Volatile
    private var stopping: Boolean = false
    private var workerGroup: EventLoopGroup? = null
    @Volatile
    private var clientChannel: Channel? = null
    // Offset into the list of targets, so that we can implement round-robin reconnect logic.
    private var targetIndex = 0
    private var currentTarget: NetworkHostAndPort = targets.first()
    private var retryInterval = MIN_RETRY_INTERVAL
    private val badCertTargets = mutableSetOf<NetworkHostAndPort>()

    private fun nextTarget() {
        val origIndex = targetIndex
        targetIndex = -1
        for (offset in 1..targets.size) {
            val newTargetIndex = (origIndex + offset).rem(targets.size)
            if (targets[newTargetIndex] !in badCertTargets) {
                targetIndex = newTargetIndex
                break
            }
        }
        if (targetIndex == -1) {
            log.error("No targets have presented acceptable certificates for $allowedRemoteLegalNames. Halting retries")
            return
        }
        log.info("Retry connect to ${targets[targetIndex]}")
        retryInterval = min(MAX_RETRY_INTERVAL, retryInterval * BACKOFF_MULTIPLIER)
    }

    private val connectListener = ChannelFutureListener { future ->
        if (!future.isSuccess) {
            log.info("Failed to connect to $currentTarget. Proxy: ${configuration.proxyConfig?.proxyAddress}")

            if (!stopping) {
                workerGroup?.schedule({
                    nextTarget()
                    restart()
                }, retryInterval, TimeUnit.MILLISECONDS)
            }
        } else {
            log.info("Connected to $currentTarget")
            // Connection established successfully
            clientChannel = future.channel()
            clientChannel?.closeFuture()?.addListener(closeListener)
        }
    }

    private val closeListener = ChannelFutureListener { future ->
        log.info("Disconnected from $currentTarget")
        future.channel()?.disconnect()
        clientChannel = null
        if (!stopping) {
            workerGroup?.schedule({
                nextTarget()
                restart()
            }, retryInterval, TimeUnit.MILLISECONDS)
        }
    }

    private class ClientChannelInitializer(val parent: AMQPClient) : ChannelInitializer<SocketChannel>() {
        private val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        private val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        private val conf = parent.configuration

        init {
            keyManagerFactory.init(conf.keyStore)
            trustManagerFactory.init(initialiseTrustStoreAndEnableCrlChecking(conf.trustStore, conf.crlCheckSoftFail))
        }

        override fun initChannel(ch: SocketChannel) {
            val pipeline = ch.pipeline()
            val proxyConfig = conf.proxyConfig
            if (proxyConfig != null) {
                if (conf.trace) pipeline.addLast(PROXY_LOGGER_NAME, LoggingHandler(LogLevel.INFO))
                val proxyAddress = InetSocketAddress(proxyConfig.proxyAddress.host, proxyConfig.proxyAddress.port)
                val proxy = when (conf.proxyConfig!!.version) {
                    ProxyVersion.SOCKS4 -> {
                        Socks4ProxyHandler(proxyAddress, proxyConfig.userName)
                    }
                    ProxyVersion.SOCKS5 -> {
                        Socks5ProxyHandler(proxyAddress, proxyConfig.userName, proxyConfig.password)
                    }
                    ProxyVersion.HTTP -> {
                        val httpProxyHandler = if(proxyConfig.userName == null || proxyConfig.password == null) {
                            HttpProxyHandler(proxyAddress)
                        } else {
                            HttpProxyHandler(proxyAddress, proxyConfig.userName, proxyConfig.password)
                        }
                        //httpProxyHandler.setConnectTimeoutMillis(3600000) // 1hr for debugging purposes
                        httpProxyHandler
                    }
                }
                val proxyTimeout = proxyConfig.proxyTimeoutMS
                if (proxyTimeout != null) {
                    proxy.setConnectTimeoutMillis(proxyTimeout)
                }
                pipeline.addLast("Proxy", proxy)
                proxy.connectFuture().addListener {
                    if (!it.isSuccess) {
                        ch.disconnect()
                    }
                }
            }

            val wrappedKeyManagerFactory = CertHoldingKeyManagerFactoryWrapper(keyManagerFactory, parent.configuration)
            val target = parent.currentTarget
            val handler = if (parent.configuration.useOpenSsl) {
                createClientOpenSslHandler(target, parent.allowedRemoteLegalNames, wrappedKeyManagerFactory, trustManagerFactory, ch.alloc())
            } else {
                createClientSslHelper(target, parent.allowedRemoteLegalNames, wrappedKeyManagerFactory, trustManagerFactory)
            }
            pipeline.addLast("sslHandler", handler)
            if (conf.trace) pipeline.addLast("logger", LoggingHandler(LogLevel.INFO))
            pipeline.addLast(AMQPChannelHandler(false,
                    parent.allowedRemoteLegalNames,
                    // Single entry, key can be anything.
                    mapOf(DEFAULT to wrappedKeyManagerFactory),
                    conf.userName,
                    conf.password,
                    conf.trace,
                    false,
                    { _, change ->
                        parent.retryInterval = MIN_RETRY_INTERVAL // reset to fast reconnect if we connect properly
                        parent._onConnection.onNext(change)
                    },
                    { _, change ->
                        parent._onConnection.onNext(change)
                        if (change.badCert) {
                            log.error("Blocking future connection attempts to $target due to bad certificate on endpoint")
                            parent.badCertTargets += target
                        }
                    },
                    { rcv -> parent._onReceive.onNext(rcv) }))
        }
    }

    fun start() {
        lock.withLock {
            log.info("connect to: $currentTarget")
            workerGroup = sharedThreadPool ?: NioEventLoopGroup(NUM_CLIENT_THREADS)
            restart()
        }
    }

    private fun restart() {
        if (targetIndex == -1) {
            return
        }
        val bootstrap = Bootstrap()
        // TODO Needs more configuration control when we profile. e.g. to use EPOLL on Linux
        bootstrap.group(workerGroup).channel(NioSocketChannel::class.java).handler(ClientChannelInitializer(this))
        // Delegate DNS Resolution to the proxy side, if we are using proxy.
        if (configuration.proxyConfig != null) {
            bootstrap.resolver(NoopAddressResolverGroup.INSTANCE)
        }
        currentTarget = targets[targetIndex]
        val clientFuture = bootstrap.connect(currentTarget.host, currentTarget.port)
        clientFuture.addListener(connectListener)
    }

    fun stop() {
        lock.withLock {
            log.info("disconnect from: $currentTarget")
            stopping = true
            try {
                if (sharedThreadPool == null) {
                    workerGroup?.shutdownGracefully()
                    workerGroup?.terminationFuture()?.sync()
                } else {
                    clientChannel?.close()?.sync()
                }
                clientChannel = null
                workerGroup = null
            } finally {
                stopping = false
            }
            log.info("stopped connection to $currentTarget")
        }
    }

    override fun close() = stop()

    val connected: Boolean
        get() {
            val channel = lock.withLock { clientChannel }
            return channel?.isActive ?: false
        }

    fun createMessage(payload: ByteArray,
                      topic: String,
                      destinationLegalName: String,
                      properties: Map<String, Any?>): SendableMessage {
        requireMessageSize(payload.size, configuration.maxMessageSize)
        return SendableMessageImpl(payload, topic, destinationLegalName, currentTarget, properties)
    }

    fun write(msg: SendableMessage) {
        val channel = clientChannel
        if (channel == null) {
            throw IllegalStateException("Connection to $targets not active")
        } else {
            channel.writeAndFlush(msg)
        }
    }

    private val _onReceive = PublishSubject.create<ReceivedMessage>().toSerialized()
    val onReceive: Observable<ReceivedMessage>
        get() = _onReceive

    private val _onConnection = PublishSubject.create<ConnectionChange>().toSerialized()
    val onConnection: Observable<ConnectionChange>
        get() = _onConnection
}