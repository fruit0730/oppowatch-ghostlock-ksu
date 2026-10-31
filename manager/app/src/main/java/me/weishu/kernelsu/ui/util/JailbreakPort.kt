package me.weishu.kernelsu.ui.util

import java.net.InetSocketAddress
import java.net.Socket

/**
 * GhostLock leaves a root shell on 127.0.0.1:25555 after a successful fire.
 * The port is the jailbreak's liveness signal; probing it is cheap and avoids
 * spawning a shell (and a libsu `su` lookup) on every UI recomposition.
 */
object JailbreakPort {
    const val PORT = 25555

    @Volatile
    private var checkedAt = 0L

    @Volatile
    private var reachable = false

    @Volatile
    private var uidCheckedAt = 0L

    @Volatile
    private var rootVerified = false

    fun isReachable(force: Boolean = false, ttlMillis: Long = 2_000L): Boolean {
        val now = System.currentTimeMillis()
        if (!force && now - checkedAt < ttlMillis) return reachable
        reachable = runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", PORT), 500)
            }
            true
        }.getOrDefault(false)
        checkedAt = now
        return reachable
    }

    /**
     * A listener on 25555 is not enough: only uid 0 means the jailbreak is
     * actually active. Ask the shell directly and cache the answer briefly.
     */
    fun isRoot(force: Boolean = false, ttlMillis: Long = 5_000L): Boolean {
        val now = System.currentTimeMillis()
        if (!force && now - uidCheckedAt < ttlMillis) return rootVerified
        rootVerified = runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", PORT), 500)
                socket.soTimeout = 1_500
                val command = "PS1=''\nunset PS1\n" +
                    "echo __KSUW_UID_BEGIN__\nid -u\necho __KSUW_UID_END__\n"
                val output = socket.getOutputStream()
                output.write(command.toByteArray())
                output.flush()

                val reader = socket.getInputStream().bufferedReader()
                val numberAtEnd = Regex("""(\d+)\s*$""")
                var inBlock = false
                var uid: Int? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    val text = line.trim()
                    when {
                        text.contains("__KSUW_UID_BEGIN__") -> inBlock = true
                        text.contains("__KSUW_UID_END__") -> break
                        inBlock -> numberAtEnd.find(text)?.groupValues?.get(1)
                            ?.toIntOrNull()?.let { uid = it }
                    }
                }
                uid == 0
            }
        }.getOrDefault(false)
        uidCheckedAt = now
        return rootVerified
    }

    fun invalidate() {
        checkedAt = 0L
        uidCheckedAt = 0L
    }
}
