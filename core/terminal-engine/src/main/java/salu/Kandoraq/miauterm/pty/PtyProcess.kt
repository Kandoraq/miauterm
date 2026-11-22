package salu.Kandoraq.miauterm.pty

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

import java.util.concurrent.atomic.AtomicBoolean

class PtyProcess private constructor(
    val masterFd: Int,
    val pid: Int,
    private val pfd: ParcelFileDescriptor
) {
    val inputStream: InputStream = FileInputStream(pfd.fileDescriptor)
    val outputStream: OutputStream = FileOutputStream(pfd.fileDescriptor)

    private val alive = AtomicBoolean(true)
    private val fdClosed = AtomicBoolean(false)
    private val fdLock = Any()
    private val processLock = Any()
    private val reapCoordinator = ProcessReapCoordinator()
    @Volatile
    private var reaped = false
    val isAlive: Boolean
        get() = alive.get()

    private fun closeMasterFdOnce() {
        synchronized(fdLock) {
            if (fdClosed.compareAndSet(false, true)) {
                try {
                    pfd.close()
                } catch (_: Exception) {}
            }
        }
    }

    fun resize(rows: Int, cols: Int, widthPx: Int, heightPx: Int) {
        synchronized(fdLock) {
            if (isAlive && !fdClosed.get() && masterFd >= 0) {
                PtyNative.setPtyWindowSize(masterFd, rows, cols, widthPx, heightPx)
            }
        }
    }

    fun waitFor(): Int = waitFor(0L)

    fun waitFor(timeoutMs: Long): Int {
        if (timeoutMs <= 0L) {
            return reapBlocking()
        }
        val thread = Thread {
            try {
                reapBlocking()
            } catch (_: Exception) {}
        }.apply { isDaemon = true; name = "Miauterm-PtyWait" }
        thread.start()
        thread.join(timeoutMs)
        if (thread.isAlive) {
            // Timed out: best-effort kill, leave reaping to a daemon.
            try { PtyNative.killProcess(pid, 9) } catch (_: Exception) {}
            return -1
        }
        return lastExitCode
    }

    @Volatile
    private var lastExitCode: Int = -1

    private fun reapBlocking(): Int {
        val code = reapCoordinator.reap { PtyNative.waitForProcess(pid) }
        synchronized(processLock) {
            reaped = true
            alive.set(false)
            lastExitCode = code
        }
        closeMasterFdOnce()
        return code
    }

    fun destroy() {
        val terminate = synchronized(processLock) {
            !reaped && alive.compareAndSet(true, false)
        }
        if (terminate) {
            try { PtyNative.killProcess(pid, 15) } catch (_: Exception) {}
            val t = Thread({
                try {
                    Thread.sleep(750)
                    if (!reaped) {
                        try { PtyNative.killProcess(pid, 9) } catch (_: Exception) {}
                    }
                    // Reap to avoid a lingering zombie.
                    try { reapBlocking() } catch (_: Exception) {}
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }, "Miauterm-PtyReaper").apply { isDaemon = true }
            t.start()
        } else if (reaped) {
            closeMasterFdOnce()
        }
    }

    companion object {
        fun create(
            cmd: String,
            args: Array<String>,
            envVars: Array<String>,
            cwd: String?,
            rows: Int,
            cols: Int,
            widthPx: Int,
            heightPx: Int
        ): PtyProcess? {
            val result = PtyNative.createPty(cmd, args, envVars, cwd, rows, cols, widthPx, heightPx) ?: return null
            val masterFd = result[0]
            val pid = result[1]
            val pfd = ParcelFileDescriptor.adoptFd(masterFd)
            return PtyProcess(masterFd, pid, pfd)
        }
    }
}
