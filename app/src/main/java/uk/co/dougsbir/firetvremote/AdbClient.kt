package uk.co.dougsbir.firetvremote

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.atomic.AtomicInteger

class AdbClient private constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: DataOutputStream
) : Closeable {
    private val nextLocalId = AtomicInteger(1)
    private val ioLock = Any()

    suspend fun shell(command: String) = withContext(Dispatchers.IO) {
        synchronized(ioLock) { runShell(command) }
    }

    private fun runShell(command: String) {
        val localId = nextLocalId.getAndIncrement()
        send(OPEN, localId, 0, ("shell:" + command + "\u0000").toByteArray())
        var remoteId = 0
        while (true) {
            val packet = receive()
            when (packet.command) {
                OKAY -> if (packet.arg1 == localId) remoteId = packet.arg0
                WRTE -> if (packet.arg1 == localId) send(OKAY, localId, packet.arg0)
                CLSE -> if (packet.arg1 == localId) {
                    send(CLSE, localId, if (remoteId != 0) remoteId else packet.arg0)
                    return
                }
            }
        }
    }

    override fun close() {
        try { socket.close() } catch (_: Exception) {}
    }

    private fun send(command: Int, arg0: Int, arg1: Int, data: ByteArray = byteArrayOf()) {
        val checksum = data.fold(0) { sum, byte -> sum + (byte.toInt() and 0xff) }
        writeLeInt(command); writeLeInt(arg0); writeLeInt(arg1); writeLeInt(data.size)
        writeLeInt(checksum); writeLeInt(command xor -1)
        output.write(data); output.flush()
    }

    private fun receive(): Packet {
        val command = readLeInt()
        val arg0 = readLeInt()
        val arg1 = readLeInt()
        val length = readLeInt()
        val checksum = readLeInt()
        val magic = readLeInt()
        require(magic == (command xor -1)) { "Invalid response from Fire TV" }
        require(length in 0..MAX_PACKET) { "Invalid ADB packet size" }
        val data = ByteArray(length)
        input.readFully(data)
        require(data.fold(0) { sum, byte -> sum + (byte.toInt() and 0xff) } == checksum) {
            "Damaged response from Fire TV"
        }
        return Packet(command, arg0, arg1, data)
    }

    private fun writeLeInt(value: Int) {
        output.writeByte(value and 0xff)
        output.writeByte(value ushr 8 and 0xff)
        output.writeByte(value ushr 16 and 0xff)
        output.writeByte(value ushr 24 and 0xff)
    }

    private fun readLeInt(): Int = input.readUnsignedByte() or
        (input.readUnsignedByte() shl 8) or
        (input.readUnsignedByte() shl 16) or
        (input.readUnsignedByte() shl 24)

    private data class Packet(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    companion object {
        private fun command(value: String): Int = value[0].code or (value[1].code shl 8) or
            (value[2].code shl 16) or (value[3].code shl 24)

        private val CNXN = command("CNXN")
        private val AUTH = command("AUTH")
        private val OPEN = command("OPEN")
        private val OKAY = command("OKAY")
        private val WRTE = command("WRTE")
        private val CLSE = command("CLSE")
        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSAPUBLICKEY = 3
        private const val MAX_PACKET = 1024 * 1024

        suspend fun connect(context: Context, host: String, port: Int): AdbClient =
            withContext(Dispatchers.IO) {
                val socket = Socket()
                try {
                    socket.connect(InetSocketAddress(host, port), 10_000)
                    socket.soTimeout = 15_000
                    socket.tcpNoDelay = true
                    val client = AdbClient(socket, DataInputStream(socket.getInputStream()),
                        DataOutputStream(socket.getOutputStream()))
                    client.authenticate(loadOrCreateKey(context))
                    client
                } catch (e: Exception) {
                    try { socket.close() } catch (_: Exception) {}
                    throw IllegalStateException(
                        "Check the IP address, Wi-Fi and that ADB Debugging is enabled. " +
                            "Accept the message on the TV, then tap Connect again.", e
                    )
                }
            }

        private fun AdbClient.authenticate(keyPair: KeyPair) {
            send(CNXN, 0x01000000, 4096, "host::features=shell_v2\u0000".toByteArray())
            var sentSignature = false
            var sentPublicKey = false
            while (true) {
                val packet = receive()
                when (packet.command) {
                    CNXN -> return
                    AUTH -> {
                        require(packet.arg0 == AUTH_TOKEN) { "Unsupported Fire TV authentication" }
                        when {
                            !sentSignature -> {
                                val signer = Signature.getInstance("SHA1withRSA")
                                signer.initSign(keyPair.private)
                                signer.update(packet.data)
                                send(AUTH, AUTH_SIGNATURE, 0, signer.sign())
                                sentSignature = true
                            }
                            !sentPublicKey -> {
                                send(AUTH, AUTH_RSAPUBLICKEY, 0, adbPublicKey(keyPair.public as RSAPublicKey))
                                sentPublicKey = true
                            }
                            else -> throw IllegalStateException("Authorization was not accepted on the TV")
                        }
                    }
                }
            }
        }

        private fun loadOrCreateKey(context: Context): KeyPair {
            val prefs = context.getSharedPreferences("adb_key", Context.MODE_PRIVATE)
            val privateText = prefs.getString("private", null)
            val publicText = prefs.getString("public", null)
            if (privateText != null && publicText != null) {
                return try {
                    val factory = KeyFactory.getInstance("RSA")
                    KeyPair(
                        factory.generatePublic(X509EncodedKeySpec(Base64.decode(publicText, Base64.NO_WRAP))),
                        factory.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privateText, Base64.NO_WRAP)))
                    )
                } catch (_: Exception) { generateAndSaveKey(prefs) }
            }
            return generateAndSaveKey(prefs)
        }

        private fun generateAndSaveKey(prefs: android.content.SharedPreferences): KeyPair {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048)
            val pair = generator.generateKeyPair()
            prefs.edit()
                .putString("private", Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP))
                .putString("public", Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP))
                .apply()
            return pair
        }

        private fun adbPublicKey(key: RSAPublicKey): ByteArray {
            val modulus = key.modulus
            val wordBase = BigInteger.ONE.shiftLeft(32)
            val n0inv = wordBase.subtract(modulus.modInverse(wordBase)).and(wordBase.subtract(BigInteger.ONE))
            val r = BigInteger.ONE.shiftLeft(2048)
            val rr = r.multiply(r).mod(modulus)
            val buffer = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(64)
            buffer.putInt(n0inv.toInt())
            buffer.put(fixedLittleEndian(modulus, 256))
            buffer.put(fixedLittleEndian(rr, 256))
            buffer.putInt(key.publicExponent.toInt())
            val encoded = Base64.encodeToString(buffer.array(), Base64.NO_WRAP)
            return (encoded + " android@firetv-remote\u0000").toByteArray()
        }

        private fun fixedLittleEndian(value: BigInteger, size: Int): ByteArray {
            val big = value.toByteArray()
            val result = ByteArray(size)
            var source = big.size - 1
            var target = 0
            while (source >= 0 && target < size) result[target++] = big[source--]
            return result
        }
    }
}
