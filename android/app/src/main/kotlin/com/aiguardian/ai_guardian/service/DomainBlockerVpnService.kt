package com.aiguardian.ai_guardian.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.aiguardian.ai_guardian.MainActivity
import com.aiguardian.ai_guardian.storage.DomainRepository
import com.aiguardian.ai_guardian.storage.PolicyDatabaseHelper
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Local VPN-based domain blocker for AI Guardian.
 *
 * Full-tunnel VPN with DNS interception:
 * - ALL traffic routes through VPN TUN (required to intercept Chrome DoH)
 * - DNS queries (UDP port 53) are intercepted and blocked/allowed
 * - Non-DNS traffic is forwarded through protected sockets (bypasses VPN)
 * - This ensures Chrome's DoH traffic also gets intercepted
 */
class DomainBlockerVpnService : VpnService() {

    companion object {
        private const val TAG = "AIGuardianVPN"
        private const val CHANNEL_ID = "aiguardian_vpn_channel"
        private const val NOTIFICATION_ID = 1001
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val DNS_PORT = 53
        private const val BUFFER_SIZE = 65535
        private const val DNS_FORWARD_TIMEOUT_MS = 5000
        private const val TCP_CONNECT_TIMEOUT_MS = 5000
        private const val MAX_TCP_CONNECTIONS = 64
        private const val MAX_UDP_RELAYS = 64

        @Volatile
        var isRunning = false
            private set

        private val blockedDomains = ConcurrentHashMap.newKeySet<String>()

        fun start(context: Context) {
            val intent = Intent(context, DomainBlockerVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DomainBlockerVpnService::class.java))
        }

        fun updateBlockedDomains(domains: Set<String>) {
            blockedDomains.clear()
            blockedDomains.addAll(domains)
            Log.i(TAG, "Updated blocked domains: ${domains.size} entries")
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val isStopping = AtomicBoolean(false)
    private var vpnThread: Thread? = null

    // TCP connection tracking
    private data class TcpConnection(
        val socket: java.nio.channels.SocketChannel,
        val srcIp: ByteArray,
        val srcPort: Int,
        val dstIp: ByteArray,
        val dstPort: Int,
        var localSeq: Long = 0,
        var remoteSeq: Long = 0,
        var established: Boolean = false,
    )

    private val tcpConnections = ConcurrentHashMap<String, TcpConnection>()

    // UDP relay tracking
    private data class UdpRelay(
        val socket: DatagramSocket,
        val srcIp: ByteArray,
        val srcPort: Int,
        val dstIp: ByteArray,
        val dstPort: Int,
    )

    private val udpRelays = ConcurrentHashMap<String, UdpRelay>()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate()")

        createNotificationChannel()

        try {
            startForeground(NOTIFICATION_ID, createNotification())
            Log.i(TAG, "Foreground service started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground: ${e.message}", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand called")

        if (isRunning) {
            Log.d(TAG, "VPN already running, returning START_STICKY")
            return START_STICKY
        }

        isStopping.set(false)

        // Load blocked domains from database
        try {
            val dbHelper = PolicyDatabaseHelper(applicationContext)
            val domainRepo = DomainRepository(dbHelper)
            val domains = domainRepo.getBlockedDomainSet()
            blockedDomains.clear()
            blockedDomains.addAll(domains)
            Log.i(TAG, "Loaded ${domains.size} blocked domains from database")
            domains.forEach { Log.d(TAG, "  Domain: $it") }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load blocked domains: ${e.message}", e)
            return START_NOT_STICKY
        }

        Log.i(TAG, "Attempting to establish VPN interface...")
        if (!establishVpn()) {
            Log.e(TAG, "FAILED to establish VPN interface — stopping service")
            stopSelf()
            return START_NOT_STICKY
        }
        Log.i(TAG, "VPN interface established successfully")

        startPacketProcessing()

        isRunning = true
        Log.i(TAG, "VPN service STARTED with ${blockedDomains.size} blocked domains")

        return START_STICKY
    }

    override fun onDestroy() {
        isStopping.set(true)
        isRunning = false

        vpnThread?.interrupt()
        vpnThread = null

        // Close all TCP connections
        tcpConnections.values.forEach { conn ->
            try { conn.socket.close() } catch (_: Exception) {}
        }
        tcpConnections.clear()

        // Close all UDP relays
        udpRelays.values.forEach { relay ->
            try { relay.socket.close() } catch (_: Exception) {}
        }
        udpRelays.clear()

        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing VPN interface: ${e.message}")
        }
        vpnInterface = null

        Log.i(TAG, "VPN service destroyed")
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN revoked")
        stopSelf()
        super.onRevoke()
    }

    private fun establishVpn(): Boolean {
        return try {
            Log.i(TAG, "establishVpn() called")

            // Full-tunnel VPN: route ALL traffic through TUN.
            // This ensures Chrome's DoH (HTTPS to dns.google) also enters the TUN.
            // Non-DNS traffic is forwarded via protected sockets.
            val builder = Builder()
                .setSession("AI Guardian")
                .addAddress(VPN_ADDRESS, 32)
                .addRoute("0.0.0.0", 0)         // Route ALL IPv4 traffic through TUN
                .addRoute("::", 0)                // Route ALL IPv6 traffic through TUN
                .addDnsServer("8.8.8.8")         // Set DNS server
                .addDnsServer("8.8.4.4")
                .setMtu(1500)

            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                Log.e(TAG, "establish() returned null — VPN consent may not have been granted")
                false
            } else {
                Log.i(TAG, "VPN interface established, fd=${vpnInterface?.fileDescriptor}")
                true
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: ${e.message}", e)
            false
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "IllegalArgumentException: ${e.message}", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Exception: ${e.javaClass.simpleName}: ${e.message}", e)
            false
        }
    }

    private fun startPacketProcessing() {
        vpnThread = Thread({
            packetProcessingLoop()
        }, "DomainBlocker-Packets").also { it.start() }
    }

    /**
     * Main packet processing loop.
     * Reads IP packets from the VPN tunnel and processes them.
     */
    private fun packetProcessingLoop() {
        val fd = vpnInterface?.fileDescriptor ?: run {
            Log.e(TAG, "VPN interface file descriptor is null!")
            return
        }
        val input = FileInputStream(fd)
        val output = FileOutputStream(fd)
        val buffer = ByteArray(BUFFER_SIZE)

        Log.i(TAG, "Packet processing loop STARTED")

        var packetCount = 0
        var diagPacketsLogged = 0
        val maxDiagPackets = 20

        while (!isStopping.get()) {
            try {
                val length = input.read(buffer)
                if (length <= 0) {
                    Thread.sleep(10)
                    continue
                }

                packetCount++

                // Log diagnostic info for first few packets
                if (diagPacketsLogged < maxDiagPackets) {
                    try {
                        val versionIhl = buffer[0].toInt() and 0xFF
                        val version = (versionIhl shr 4) and 0xF
                        val ihl = (versionIhl and 0xF) * 4
                        val protocol = buffer[9].toInt() and 0xFF
                        val srcIp = formatIp(buffer.copyOfRange(12, 16))
                        val dstIp = formatIp(buffer.copyOfRange(16, 20))

                        Log.d(TAG, "[DIAG] #$packetCount: len=$length, v=$version, proto=$protocol, src=$srcIp, dst=$dstIp")

                        if (protocol == 17 && length >= ihl + 8) {
                            val dstPort = ((buffer[ihl + 2].toInt() and 0xFF) shl 8) or (buffer[ihl + 3].toInt() and 0xFF)
                            Log.d(TAG, "[DIAG]   UDP dst_port=$dstPort")
                        } else if (protocol == 6 && length >= ihl + 20) {
                            val dstPort = ((buffer[ihl + 2].toInt() and 0xFF) shl 8) or (buffer[ihl + 3].toInt() and 0xFF)
                            Log.d(TAG, "[DIAG]   TCP dst_port=$dstPort")
                        }
                        diagPacketsLogged++
                    } catch (e: Exception) {
                        Log.w(TAG, "[DIAG] Error parsing packet: ${e.message}")
                    }
                }

                if (packetCount % 500 == 0) {
                    Log.d(TAG, "Processed $packetCount packets total. TCP connections: ${tcpConnections.size}, UDP relays: ${udpRelays.size}")
                }

                processPacket(buffer, length, output)

            } catch (e: InterruptedException) {
                Log.i(TAG, "Packet loop interrupted")
                break
            } catch (e: Exception) {
                if (!isStopping.get()) {
                    Log.w(TAG, "Error in packet loop: ${e.message}")
                }
            }
        }

        try {
            input.close()
            output.close()
        } catch (_: Exception) {}

        Log.i(TAG, "Packet processing loop ENDED after $packetCount packets")
    }

    /**
     * Process a single IP packet from the VPN tunnel.
     */
    private fun processPacket(buffer: ByteArray, length: Int, output: FileOutputStream) {
        if (length < 20) return

        val versionIhl = buffer[0].toInt() and 0xFF
        val version = (versionIhl shr 4) and 0xF
        if (version != 4) return

        val ihl = (versionIhl and 0xF) * 4
        if (ihl > length) return
        val protocol = buffer[9].toInt() and 0xFF
        val srcIp = buffer.copyOfRange(12, 16)
        val dstIp = buffer.copyOfRange(16, 20)

        when (protocol) {
            17 -> handleUdp(buffer, length, ihl, srcIp, dstIp, output)
            6 -> handleTcp(buffer, length, ihl, srcIp, dstIp, output)
            else -> { /* Drop non-UDP/TCP */ }
        }
    }

    // ─── UDP HANDLING ───────────────────────────────────────────────

    private fun handleUdp(
        buffer: ByteArray, length: Int, ihl: Int,
        srcIp: ByteArray, dstIp: ByteArray,
        output: FileOutputStream
    ) {
        if (length < ihl + 8) return

        val srcPort = ((buffer[ihl].toInt() and 0xFF) shl 8) or (buffer[ihl + 1].toInt() and 0xFF)
        val dstPort = ((buffer[ihl + 2].toInt() and 0xFF) shl 8) or (buffer[ihl + 3].toInt() and 0xFF)

        // DNS query (port 53): intercept
        if (dstPort == DNS_PORT) {
            handleDns(buffer, length, ihl, srcIp, srcPort, dstIp, output)
            return
        }

        // Check if this is a response from an existing UDP relay
        val relayKey = "${formatIp(srcIp)}:$dstPort:${formatIp(dstIp)}:$srcPort"
        val relay = udpRelays[relayKey]
        if (relay != null) {
            // Response from relay — forward back to TUN client
            handleUdpRelayResponse(buffer, length, ihl, srcIp, srcPort, dstIp, dstPort, relay, output)
            return
        }

        // Non-DNS UDP: forward via protected socket
        forwardUdp(buffer, length, ihl, srcIp, srcPort, dstIp, dstPort, output)
    }

    private fun handleDns(
        buffer: ByteArray, length: Int, ihl: Int,
        srcIp: ByteArray, srcPort: Int,
        dstIp: ByteArray,
        output: FileOutputStream
    ) {
        val dnsOffset = ihl + 8
        val dnsLength = length - dnsOffset
        if (dnsLength < 12) return

        val domain = extractDnsQueryDomain(buffer, dnsOffset, dnsLength)

        if (domain != null) {
            val isBlocked = isDomainBlocked(domain)
            Log.i(TAG, "[DNS] QUERY: $domain | BLOCKED: $isBlocked | src=${formatIp(srcIp)}:$srcPort")

            if (isBlocked) {
                Log.i(TAG, "[DNS] DECISION: BLOCK -> Sending NXDOMAIN for $domain")
                sendDnsBlockedResponse(buffer, length, ihl, output)
            } else {
                Log.d(TAG, "[DNS] DECISION: ALLOW -> Forwarding $domain to upstream DNS")
                forwardDnsQuery(buffer, length, ihl, srcIp, srcPort, output)
            }
        } else {
            Log.w(TAG, "[DNS] Failed to extract domain, dropping")
        }
    }

    private fun forwardUdp(
        buffer: ByteArray, length: Int, ihl: Int,
        srcIp: ByteArray, srcPort: Int,
        dstIp: ByteArray, dstPort: Int,
        output: FileOutputStream
    ) {
        if (udpRelays.size >= MAX_UDP_RELAYS) {
            Log.w(TAG, "[UDP] Max UDP relays reached, dropping")
            return
        }

        try {
            val payloadOffset = ihl + 8
            val payloadLength = length - payloadOffset
            if (payloadLength <= 0) return

            val payload = buffer.copyOfRange(payloadOffset, payloadOffset + payloadLength)

            val socket = DatagramSocket(null)
            protect(socket)
            socket.soTimeout = 3000
            socket.reuseAddress = true

            val dstInetAddress = InetAddress.getByAddress(dstIp)
            socket.connect(dstInetAddress, dstPort)

            val sendPacket = DatagramPacket(payload, payload.size)
            socket.send(sendPacket)

            val relayKey = "${formatIp(dstIp)}:$dstPort:${formatIp(srcIp)}:$srcPort"
            val relay = UdpRelay(socket, dstIp.clone(), dstPort, srcIp.clone(), srcPort)
            udpRelays[relayKey] = relay

            // Read response in background thread
            Thread({
                try {
                    val responseBuf = ByteArray(BUFFER_SIZE)
                    val responsePacket = DatagramPacket(responseBuf, responseBuf.size)
                    socket.receive(responsePacket)

                    if (!isStopping.get()) {
                        val responsePayload = responsePacket.data.copyOfRange(0, responsePacket.length)
                        val responsePacket = buildUdpPacket(
                            srcIp = dstIp.clone(),
                            srcPort = dstPort,
                            dstIp = srcIp.clone(),
                            dstPort = srcPort,
                            payload = responsePayload
                        )
                        synchronized(output) {
                            output.write(responsePacket, 0, responsePacket.size)
                            output.flush()
                        }
                        Log.d(TAG, "[UDP] Relay response: ${responsePacket.size} bytes")
                    }
                } catch (_: SocketTimeoutException) {
                    // Timeout, clean up
                } catch (e: Exception) {
                    if (!isStopping.get()) {
                        Log.w(TAG, "[UDP] Relay error: ${e.message}")
                    }
                } finally {
                    udpRelays.remove(relayKey)
                    try { socket.close() } catch (_: Exception) {}
                }
            }, "UDP-Relay-${formatIp(srcIp)}:$srcPort").start()

        } catch (e: Exception) {
            Log.w(TAG, "[UDP] Forward failed: ${e.message}")
        }
    }

    private fun handleUdpRelayResponse(
        buffer: ByteArray, length: Int, ihl: Int,
        srcIp: ByteArray, srcPort: Int,
        dstIp: ByteArray, dstPort: Int,
        relay: UdpRelay,
        output: FileOutputStream
    ) {
        // Already handled by the relay thread
    }

    private fun buildUdpPacket(
        srcIp: ByteArray, srcPort: Int,
        dstIp: ByteArray, dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpPayloadLen = 8 + payload.size
        val ipTotalLen = 20 + udpPayloadLen

        val packet = ByteArray(ipTotalLen)

        // IP header
        packet[0] = 0x45.toByte()
        packet[1] = 0x00
        packet[2] = (ipTotalLen shr 8).toByte()
        packet[3] = ipTotalLen.toByte()
        packet[4] = 0x00
        packet[5] = 0x00
        packet[6] = 0x40.toByte()
        packet[7] = 0x00
        packet[8] = 0x40.toByte()
        packet[9] = 0x11 // UDP
        packet[10] = 0x00
        packet[11] = 0x00
        System.arraycopy(srcIp, 0, packet, 12, 4)
        System.arraycopy(dstIp, 0, packet, 16, 4)

        val ipChecksum = calculateIpChecksum(packet, 20)
        packet[10] = (ipChecksum shr 8).toByte()
        packet[11] = ipChecksum.toByte()

        // UDP header
        val udpStart = 20
        packet[udpStart] = (srcPort shr 8).toByte()
        packet[udpStart + 1] = srcPort.toByte()
        packet[udpStart + 2] = (dstPort shr 8).toByte()
        packet[udpStart + 3] = dstPort.toByte()
        packet[udpStart + 4] = (udpPayloadLen shr 8).toByte()
        packet[udpStart + 5] = udpPayloadLen.toByte()
        packet[udpStart + 6] = 0x00
        packet[udpStart + 7] = 0x00

        System.arraycopy(payload, 0, packet, 28, payload.size)

        return packet
    }

    // ─── TCP HANDLING ───────────────────────────────────────────────

    private fun handleTcp(
        buffer: ByteArray, length: Int, ihl: Int,
        srcIp: ByteArray, dstIp: ByteArray,
        output: FileOutputStream
    ) {
        if (length < ihl + 20) return

        val srcPort = ((buffer[ihl].toInt() and 0xFF) shl 8) or (buffer[ihl + 1].toInt() and 0xFF)
        val dstPort = ((buffer[ihl + 2].toInt() and 0xFF) shl 8) or (buffer[ihl + 3].toInt() and 0xFF)
        val seqNum = (((buffer[ihl + 4].toInt() and 0xFF) shl 24) or
                ((buffer[ihl + 5].toInt() and 0xFF) shl 16) or
                ((buffer[ihl + 6].toInt() and 0xFF) shl 8) or
                (buffer[ihl + 7].toInt() and 0xFF)).toLong() and 0xFFFFFFFFL
        val ackNum = (((buffer[ihl + 8].toInt() and 0xFF) shl 24) or
                ((buffer[ihl + 9].toInt() and 0xFF) shl 16) or
                ((buffer[ihl + 10].toInt() and 0xFF) shl 8) or
                (buffer[ihl + 11].toInt() and 0xFF)).toLong() and 0xFFFFFFFFL
        val dataOffset = ((buffer[ihl + 12].toInt() and 0xFF) shr 4) * 4
        val flags = buffer[ihl + 13].toInt() and 0xFF
        val syn = (flags and 0x02) != 0
        val ack = (flags and 0x10) != 0
        val fin = (flags and 0x01) != 0
        val rst = (flags and 0x04) != 0

        val connKey = "${formatIp(srcIp)}:$srcPort-${formatIp(dstIp)}:$dstPort"

        // Handle SYN: create new connection
        if (syn && !ack) {
            if (tcpConnections.size >= MAX_TCP_CONNECTIONS) {
                Log.w(TAG, "[TCP] Max connections reached, sending RST")
                sendTcpRst(buffer, ihl, srcIp, srcPort, dstIp, dstPort, seqNum, ackNum, output)
                return
            }

            Log.i(TAG, "[TCP] SYN: $connKey")
            Thread({
                establishTcpConnection(connKey, srcIp.clone(), srcPort, dstIp.clone(), dstPort, seqNum, output)
            }, "TCP-SYN-${srcPort}").start()
            return
        }

        // Handle FIN
        if (fin) {
            val conn = tcpConnections.remove(connKey)
            if (conn != null) {
                Log.i(TAG, "[TCP] FIN: $connKey")
                sendTcpAck(conn, buffer, ihl, srcIp, srcPort, dstIp, dstPort, seqNum, ackNum, output)
                try { conn.socket.close() } catch (_: Exception) {}
            }
            return
        }

        // Handle RST
        if (rst) {
            val conn = tcpConnections.remove(connKey)
            if (conn != null) {
                Log.i(TAG, "[TCP] RST: $connKey")
                try { conn.socket.close() } catch (_: Exception) {}
            }
            return
        }

        // Handle data
        val conn = tcpConnections[connKey]
        if (conn != null && ack) {
            val payloadOffset = ihl + dataOffset
            val payloadLength = length - payloadOffset

            if (payloadLength > 0) {
                val payload = buffer.copyOfRange(payloadOffset, payloadOffset + payloadLength)
                try {
                    conn.socket.write(java.nio.ByteBuffer.wrap(payload))
                    Log.d(TAG, "[TCP] Data sent: ${payloadLength} bytes to ${conn.socket.remoteAddress}")
                } catch (e: Exception) {
                    Log.w(TAG, "[TCP] Write failed: ${e.message}")
                    tcpConnections.remove(connKey)
                    try { conn.socket.close() } catch (_: Exception) {}
                }
            }

            // Send ACK back to client
            sendTcpAck(conn, buffer, ihl, srcIp, srcPort, dstIp, dstPort, seqNum + payloadLength.toLong(), ackNum, output)
        }
    }

    private fun establishTcpConnection(
        connKey: String,
        srcIp: ByteArray, srcPort: Int,
        dstIp: ByteArray, dstPort: Int,
        clientSeq: Long,
        output: FileOutputStream
    ) {
        try {
            val socket = java.nio.channels.SocketChannel.open()
            protect(socket.socket())

            socket.configureBlocking(true)
            socket.socket().soTimeout = TCP_CONNECT_TIMEOUT_MS

            val dstInetAddress = InetAddress.getByAddress(dstIp)
            socket.connect(InetSocketAddress(dstInetAddress, dstPort))

            val conn = TcpConnection(
                socket = socket,
                srcIp = srcIp,
                srcPort = srcPort,
                dstIp = dstIp,
                dstPort = dstPort,
                localSeq = 1000L,
                remoteSeq = clientSeq + 1,
            )

            tcpConnections[connKey] = conn

            // Send SYN-ACK to client
            val synAckPacket = buildTcpPacket(
                srcIp = dstIp, srcPort = dstPort,
                dstIp = srcIp, dstPort = srcPort,
                seqNum = conn.localSeq,
                ackNum = conn.remoteSeq,
                flags = 0x12, // SYN+ACK
                payload = ByteArray(0)
            )

            conn.localSeq++

            synchronized(output) {
                output.write(synAckPacket, 0, synAckPacket.size)
                output.flush()
            }

            Log.i(TAG, "[TCP] SYN-ACK sent for $connKey")

            // Read responses from remote server and forward to TUN
            Thread({
                readRemoteData(conn, connKey, output)
            }, "TCP-Read-${connKey}").start()

        } catch (e: Exception) {
            Log.w(TAG, "[TCP] Connection failed for $connKey: ${e.message}")
            tcpConnections.remove(connKey)
        }
    }

    private fun readRemoteData(conn: TcpConnection, connKey: String, output: FileOutputStream) {
        try {
            val buf = java.nio.ByteBuffer.allocate(BUFFER_SIZE)
            while (!isStopping.get() && conn.socket.isOpen) {
                buf.clear()
                val bytesRead = conn.socket.read(buf)
                if (bytesRead == -1) {
                    Log.i(TAG, "[TCP] Remote closed: $connKey")
                    break
                }
                if (bytesRead == 0) continue

                buf.flip()
                val data = ByteArray(bytesRead)
                buf.get(data)

                // Forward data to TUN client
                val dataPacket = buildTcpPacket(
                    srcIp = conn.dstIp, srcPort = conn.dstPort,
                    dstIp = conn.srcIp, dstPort = conn.srcPort,
                    seqNum = conn.localSeq,
                    ackNum = conn.remoteSeq,
                    flags = 0x18, // PSH+ACK
                    payload = data
                )

                conn.localSeq += bytesRead

                synchronized(output) {
                    output.write(dataPacket, 0, dataPacket.size)
                    output.flush()
                }

                Log.d(TAG, "[TCP] Remote->TUN: $connKey, ${bytesRead} bytes")
            }
        } catch (e: Exception) {
            if (!isStopping.get()) {
                Log.w(TAG, "[TCP] Remote read error for $connKey: ${e.message}")
            }
        } finally {
            // Send FIN
            try {
                val finPacket = buildTcpPacket(
                    srcIp = conn.dstIp, srcPort = conn.dstPort,
                    dstIp = conn.srcIp, dstPort = conn.srcPort,
                    seqNum = conn.localSeq,
                    ackNum = conn.remoteSeq,
                    flags = 0x11, // FIN+ACK
                    payload = ByteArray(0)
                )
                synchronized(output) {
                    output.write(finPacket, 0, finPacket.size)
                    output.flush()
                }
            } catch (_: Exception) {}

            tcpConnections.remove(connKey)
            try { conn.socket.close() } catch (_: Exception) {}
        }
    }

    private fun sendTcpAck(
        conn: TcpConnection,
        buffer: ByteArray, ihl: Int,
        srcIp: ByteArray, srcPort: Int,
        dstIp: ByteArray, dstPort: Int,
        seqNum: Long, ackNum: Long,
        output: FileOutputStream
    ) {
        val ackPacket = buildTcpPacket(
            srcIp = dstIp, srcPort = dstPort,
            dstIp = srcIp, dstPort = srcPort,
            seqNum = conn.localSeq,
            ackNum = seqNum,
            flags = 0x10, // ACK
            payload = ByteArray(0)
        )

        synchronized(output) {
            output.write(ackPacket, 0, ackPacket.size)
            output.flush()
        }
    }

    private fun sendTcpRst(
        buffer: ByteArray, ihl: Int,
        srcIp: ByteArray, srcPort: Int,
        dstIp: ByteArray, dstPort: Int,
        seqNum: Long, ackNum: Long,
        output: FileOutputStream
    ) {
        val rstPacket = buildTcpPacket(
            srcIp = dstIp, srcPort = dstPort,
            dstIp = srcIp, dstPort = srcPort,
            seqNum = ackNum,
            ackNum = seqNum + 1,
            flags = 0x14, // RST+ACK
            payload = ByteArray(0)
        )

        synchronized(output) {
            output.write(rstPacket, 0, rstPacket.size)
            output.flush()
        }
    }

    private fun buildTcpPacket(
        srcIp: ByteArray, srcPort: Int,
        dstIp: ByteArray, dstPort: Int,
        seqNum: Long, ackNum: Long,
        flags: Int,
        payload: ByteArray
    ): ByteArray {
        val tcpHeaderLen = 20
        val ipHeaderLen = 20
        val ipTotalLen = ipHeaderLen + tcpHeaderLen + payload.size

        val packet = ByteArray(ipTotalLen)

        // IP header
        packet[0] = 0x45.toByte()
        packet[1] = 0x00
        packet[2] = (ipTotalLen shr 8).toByte()
        packet[3] = ipTotalLen.toByte()
        packet[4] = 0x00
        packet[5] = 0x00
        packet[6] = 0x40.toByte()
        packet[7] = 0x00
        packet[8] = 0x40.toByte()
        packet[9] = 0x06 // TCP
        packet[10] = 0x00
        packet[11] = 0x00
        System.arraycopy(srcIp, 0, packet, 12, 4)
        System.arraycopy(dstIp, 0, packet, 16, 4)

        val ipChecksum = calculateIpChecksum(packet, ipHeaderLen)
        packet[10] = (ipChecksum shr 8).toByte()
        packet[11] = ipChecksum.toByte()

        // TCP header
        val tcpStart = ipHeaderLen
        packet[tcpStart] = (srcPort shr 8).toByte()
        packet[tcpStart + 1] = srcPort.toByte()
        packet[tcpStart + 2] = (dstPort shr 8).toByte()
        packet[tcpStart + 3] = dstPort.toByte()
        packet[tcpStart + 4] = (seqNum shr 24).toByte()
        packet[tcpStart + 5] = (seqNum shr 16).toByte()
        packet[tcpStart + 6] = (seqNum shr 8).toByte()
        packet[tcpStart + 7] = seqNum.toByte()
        packet[tcpStart + 8] = (ackNum shr 24).toByte()
        packet[tcpStart + 9] = (ackNum shr 16).toByte()
        packet[tcpStart + 10] = (ackNum shr 8).toByte()
        packet[tcpStart + 11] = ackNum.toByte()
        packet[tcpStart + 12] = 0x50.toByte() // Data offset: 5 * 4 = 20
        packet[tcpStart + 13] = flags.toByte()
        packet[tcpStart + 14] = 0xFF.toByte() // Window
        packet[tcpStart + 15] = 0xFF.toByte()
        packet[tcpStart + 16] = 0x00 // Checksum
        packet[tcpStart + 17] = 0x00
        packet[tcpStart + 18] = 0x00 // Urgent pointer
        packet[tcpStart + 19] = 0x00

        // Payload
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, packet, ipHeaderLen + tcpHeaderLen, payload.size)
        }

        // TCP checksum (pseudo-header)
        val tcpChecksum = calculateTcpChecksum(packet, ipHeaderLen, tcpHeaderLen + payload.size, srcIp, dstIp)
        packet[tcpStart + 16] = (tcpChecksum shr 8).toByte()
        packet[tcpStart + 17] = tcpChecksum.toByte()

        return packet
    }

    private fun calculateTcpChecksum(
        packet: ByteArray, tcpOffset: Int, tcpLength: Int,
        srcIp: ByteArray, dstIp: ByteArray
    ): Int {
        var sum = 0L

        // Pseudo-header
        for (i in srcIp.indices step 2) {
            sum += ((srcIp[i].toInt() and 0xFF) shl 8) or (srcIp[i + 1].toInt() and 0xFF)
        }
        for (i in dstIp.indices step 2) {
            sum += ((dstIp[i].toInt() and 0xFF) shl 8) or (dstIp[i + 1].toInt() and 0xFF)
        }
        sum += 6 // TCP protocol
        sum += tcpLength

        // TCP header + data
        val savedChecksum1 = packet[tcpOffset + 16].toInt() and 0xFF
        val savedChecksum2 = packet[tcpOffset + 17].toInt() and 0xFF
        packet[tcpOffset + 16] = 0
        packet[tcpOffset + 17] = 0

        for (i in tcpOffset until tcpOffset + tcpLength step 2) {
            val word = if (i + 1 < tcpOffset + tcpLength) {
                ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            } else {
                (packet[i].toInt() and 0xFF) shl 8
            }
            sum += word
        }

        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        val checksum = sum.toInt().inv() and 0xFFFF

        // Restore checksum
        packet[tcpOffset + 16] = savedChecksum1.toByte()
        packet[tcpOffset + 17] = savedChecksum2.toByte()

        return checksum
    }

    // ─── DNS INTERCEPTOR ────────────────────────────────────────────

    private fun extractDnsQueryDomain(buffer: ByteArray, dnsOffset: Int, dnsLength: Int): String? {
        try {
            val flags = ((buffer[dnsOffset + 2].toInt() and 0xFF) shl 8) or
                    (buffer[dnsOffset + 3].toInt() and 0xFF)
            val isQuery = (flags and 0x8000) == 0
            if (!isQuery) return null

            val domain = StringBuilder()
            var offset = dnsOffset + 12

            while (offset < dnsOffset + dnsLength) {
                val labelLength = buffer[offset].toInt() and 0xFF
                if (labelLength == 0) break

                offset++
                if (offset + labelLength > dnsOffset + dnsLength) return null

                val label = String(buffer, offset, labelLength)
                if (domain.isNotEmpty()) domain.append(".")
                domain.append(label)
                offset += labelLength
            }

            return domain.toString().lowercase()
        } catch (e: Exception) {
            return null
        }
    }

    private fun isDomainBlocked(domain: String): Boolean {
        if (blockedDomains.contains(domain)) return true

        val parts = domain.split(".")
        for (i in 1 until parts.size) {
            val parentDomain = parts.subList(i, parts.size).joinToString(".")
            if (blockedDomains.contains(parentDomain)) return true
        }

        return false
    }

    private fun forwardDnsQuery(
        originalBuffer: ByteArray,
        originalLength: Int,
        ipHeaderLength: Int,
        srcIp: ByteArray,
        originalSrcPort: Int,
        output: FileOutputStream,
    ) {
        val dnsOffset = ipHeaderLength + 8
        val dnsLength = originalLength - dnsOffset

        try {
            val dnsData = ByteArray(dnsLength)
            System.arraycopy(originalBuffer, dnsOffset, dnsData, 0, dnsLength)

            val socket = DatagramSocket(null)
            protect(socket)
            socket.soTimeout = DNS_FORWARD_TIMEOUT_MS
            socket.reuseAddress = true

            val upstreamAddr = InetAddress.getByName("8.8.8.8")
            socket.connect(upstreamAddr, DNS_PORT)

            val sendPacket = DatagramPacket(dnsData, dnsData.size)
            socket.send(sendPacket)

            val responseBuf = ByteArray(BUFFER_SIZE)
            val receivePacket = DatagramPacket(responseBuf, responseBuf.size)
            try {
                socket.receive(receivePacket)
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "[DNS-FWD] Upstream DNS timed out")
                socket.close()
                sendDnsBlockedResponse(originalBuffer, originalLength, ipHeaderLength, output)
                return
            }
            socket.close()

            val responseData = receivePacket.data
            val responseLen = receivePacket.length

            val udpPayloadLen = 8 + responseLen
            val ipTotalLen = 20 + udpPayloadLen

            val responsePacket = ByteArray(ipTotalLen)

            // IP header
            responsePacket[0] = 0x45.toByte()
            responsePacket[1] = 0x00
            responsePacket[2] = (ipTotalLen shr 8).toByte()
            responsePacket[3] = ipTotalLen.toByte()
            responsePacket[4] = 0x00
            responsePacket[5] = 0x00
            responsePacket[6] = 0x40.toByte()
            responsePacket[7] = 0x00
            responsePacket[8] = 0x40.toByte()
            responsePacket[9] = 0x11
            responsePacket[10] = 0x00
            responsePacket[11] = 0x00
            System.arraycopy(srcIp, 0, responsePacket, 12, 4) // Source = original dest (DNS server)
            System.arraycopy(originalBuffer, 16, responsePacket, 16, 4) // Dest = original source

            val ipChecksum = calculateIpChecksum(responsePacket, 20)
            responsePacket[10] = (ipChecksum shr 8).toByte()
            responsePacket[11] = ipChecksum.toByte()

            // UDP header
            val udpStart = 20
            responsePacket[udpStart] = (DNS_PORT shr 8).toByte()
            responsePacket[udpStart + 1] = DNS_PORT.toByte()
            responsePacket[udpStart + 2] = (originalSrcPort shr 8).toByte()
            responsePacket[udpStart + 3] = originalSrcPort.toByte()
            responsePacket[udpStart + 4] = (udpPayloadLen shr 8).toByte()
            responsePacket[udpStart + 5] = udpPayloadLen.toByte()
            responsePacket[udpStart + 6] = 0x00
            responsePacket[udpStart + 7] = 0x00

            System.arraycopy(responseData, 0, responsePacket, 28, responseLen)

            output.write(responsePacket, 0, ipTotalLen)
            output.flush()

            Log.d(TAG, "[DNS-FWD] Forwarded DNS response: $responseLen bytes from upstream")

        } catch (e: Exception) {
            Log.w(TAG, "[DNS-FWD] Failed to forward DNS query: ${e.message}")
            sendDnsBlockedResponse(originalBuffer, originalLength, ipHeaderLength, output)
        }
    }

    private fun sendDnsBlockedResponse(
        originalBuffer: ByteArray,
        originalLength: Int,
        ipHeaderLength: Int,
        output: FileOutputStream,
    ) {
        try {
            val dnsStart = ipHeaderLength + 8
            val dnsLength = originalLength - dnsStart

            val udpPayloadLen = 8 + dnsLength
            val ipTotalLength = ipHeaderLength + udpPayloadLen

            val responseBuffer = ByteArray(ipTotalLength)

            // Copy original IP header
            System.arraycopy(originalBuffer, 0, responseBuffer, 0, ipHeaderLength)

            // Swap IP source and destination
            System.arraycopy(originalBuffer, 12, responseBuffer, 16, 4)
            System.arraycopy(originalBuffer, 16, responseBuffer, 12, 4)

            // UDP: swap ports
            val udpStart = ipHeaderLength
            responseBuffer[udpStart] = originalBuffer[udpStart + 2]
            responseBuffer[udpStart + 1] = originalBuffer[udpStart + 3]
            responseBuffer[udpStart + 2] = originalBuffer[udpStart]
            responseBuffer[udpStart + 3] = originalBuffer[udpStart + 1]

            // Copy original DNS data
            System.arraycopy(originalBuffer, dnsStart, responseBuffer, dnsStart, dnsLength)

            // Set DNS flags: QR=1 (response), RCODE=3 (NXDOMAIN)
            responseBuffer[dnsStart + 2] = (0x81).toByte()
            responseBuffer[dnsStart + 3] = (0x83).toByte()

            // Set answer count to 0
            responseBuffer[dnsStart + 6] = 0
            responseBuffer[dnsStart + 7] = 0

            // UDP length
            responseBuffer[udpStart + 4] = (udpPayloadLen shr 8).toByte()
            responseBuffer[udpStart + 5] = udpPayloadLen.toByte()
            responseBuffer[udpStart + 6] = 0
            responseBuffer[udpStart + 7] = 0

            // IP total length
            responseBuffer[2] = (ipTotalLength shr 8).toByte()
            responseBuffer[3] = ipTotalLength.toByte()

            // IP checksum
            val checksum = calculateIpChecksum(responseBuffer, ipHeaderLength)
            responseBuffer[10] = (checksum shr 8).toByte()
            responseBuffer[11] = checksum.toByte()

            output.write(responseBuffer, 0, ipTotalLength)
            output.flush()

            Log.d(TAG, "[DNS-BLOCK] Sent NXDOMAIN response")

        } catch (e: Exception) {
            Log.w(TAG, "Failed to send blocked DNS response: ${e.message}")
        }
    }

    // ─── UTILITY ────────────────────────────────────────────────────

    private fun formatIp(ip: ByteArray): String {
        return "${ip[0].toInt() and 0xFF}.${ip[1].toInt() and 0xFF}.${ip[2].toInt() and 0xFF}.${ip[3].toInt() and 0xFF}"
    }

    private fun calculateIpChecksum(packet: ByteArray, ipHeaderLength: Int): Int {
        var sum = 0L
        packet[10] = 0
        packet[11] = 0

        for (i in 0 until ipHeaderLength step 2) {
            val word = ((packet[i].toInt() and 0xFF) shl 8) or
                    (packet[i + 1].toInt() and 0xFF)
            sum += word
        }

        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        return sum.toInt().inv() and 0xFFFF
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AI Guardian Domain Blocking",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Active domain blocking via local VPN"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("AI Guardian Active")
            .setContentText("Blocking ${blockedDomains.size} domain(s)")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
