package salu.Kandoraq.Miauterm.session

import android.content.Context
import android.util.Log
import salu.Kandoraq.Miauterm.R
import salu.Kandoraq.Miauterm.emulator.TerminalEmulator
import salu.Kandoraq.Miauterm.pty.PtyProcess
import salu.Kandoraq.Miauterm.runtime.DebianRuntime
import salu.Kandoraq.Miauterm.runtime.Environment
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

class TerminalSession(
    private val context: Context,
    var rows: Int = 24,
    var cols: Int = 80,
    var widthPx: Int = 0,
    var heightPx: Int = 0,
    var onRedraw: (() -> Unit)? = null,
    val failsafeMode: Boolean = false,
    private val requestedAccountName: String? = null,
    var sessionName: String? = null
) {
    private val tag = "TerminalSession"
    val emulator = TerminalEmulator(rows, cols) {
        onRedraw?.invoke()
    }
    private var ptyProcess: PtyProcess? = null
    private var readerThread: Thread? = null
    private val rootfsRecoveryStarted = AtomicBoolean(false)
    private val starting = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)
    @Volatile
    var isRunning = false
        private set

    var onSessionFinished: ((Int) -> Unit)? = null
    var onCloseRequested: (() -> Unit)? = null
    var exitCode: Int? = null
        private set
    var title = "Miauterm"
    @Volatile var accountName: String = "root"
        private set
    fun promptLabel(): String = sessionName?.takeIf { it.isNotBlank() }
        ?: sessionPromptLabel(accountName, currentDirectory() ?: startDirectory)

    var numberProvider: (() -> Int)? = null

    var startDirectory: String? = null

    @Volatile
    private var guestWorkingDirectory: String? = null
    var onWorkingDirectoryChanged: (() -> Unit)? = null

    var restoredOutput: String? = null

    fun currentDirectory(): String? {
        guestWorkingDirectory?.takeIf { path ->
            path.startsWith("/") && path.split('/').none { it == ".." }
        }?.let { return it }
        val pid = ptyProcess?.pid ?: return null
        if (!isRunning) return startDirectory
        return try {
            android.system.Os.readlink("/proc/$pid/cwd").takeIf { it.startsWith("/") }
        } catch (e: Exception) {
            null
        }
    }

    fun transcript(): String = emulator.lock.withLock {
        SessionStore.trimOutput(emulator.buffer.transcript(SessionStore.MAX_LINES))
    }

    fun start() {
        if (isRunning || destroyed.get() || !starting.compareAndSet(false, true)) return
        var stage = "preparing session"
        try {
            val shell: String
            val args: Array<String>
            val ptyEnv: Array<String>
            val cwd: File
            if (failsafeMode) {
                stage = "preparing Android shell"
                accountName = "shell"
                shell = if (File("/system/bin/sh").canExecute()) "/system/bin/sh" else "/bin/sh"
                args = arrayOf("-i")
                val home = context.filesDir
                cwd = home
                ptyEnv = failsafeEnvironment(home, context.cacheDir)
                DebianRuntime.recordRuntimeEvent(context, "Failsafe session requested; home=${home.absolutePath}")
            } else {
                val root = Environment.getMiautermRoot(context)
                if (root.isDirectory) {
                    stage = "checking Debian usr-merge links"
                    DebianRuntime.repairUsrMergeLinks(root)
                    DebianRuntime.recordRuntimeEvent(
                        context,
                        "Debian usr-merge links checked: /bin -> usr/bin, /sbin -> usr/sbin, /lib -> usr/lib"
                    )
                }
                var shellPath = DebianRuntime.shellPathForSession(root)
                if (shellPath == null) {
                    DebianRuntime.recordRuntimeEvent(context, "Shell path missing; validating and repairing Debian usr-merge links")
                    runCatching { DebianRuntime.verifyRootfs(root) }
                    shellPath = DebianRuntime.shellPathForSession(root)
                }
                val launcher = File(context.filesDir, "proot/launcher.sh")
                val account = requestedAccountName?.let { DebianRuntime.accountByName(root, it) }
                    ?: if (requestedAccountName == null) DebianRuntime.selectedAccount(context, root)
                    else throw IllegalArgumentException("Linux account '$requestedAccountName' is not available")
                accountName = account.name
                val rootCwd = File(root, account.home.removePrefix("/"))
                stage = "checking Linux rootfs files"
                DebianRuntime.recordRuntimeEvent(
                    context,
                    "Session start requested; failsafe=$failsafeMode root=${root.absolutePath}"
                )
                check(root.isDirectory && rootCwd.isDirectory) {
                    "Debian storage is missing at ${root.absolutePath}. Restart Miauterm to install the bundled Debian system."
                }
                if (shellPath == null || !shellPath.isFile || !shellPath.canExecute()) {
                    val probe = DebianRuntime.shellProbeSummary(root)
                    DebianRuntime.recordRuntimeEvent(context, "Debian shell probe failed: $probe")
                    if (!DebianRuntime.isRemoved(context) && rootfsRecoveryStarted.compareAndSet(false, true)) {
                        val notice = "\r\nDebian shell is missing. Repairing the bundled Linux system...\r\n"
                        val noticeBytes = notice.toByteArray(Charsets.UTF_8)
                        emulator.processInput(noticeBytes, 0, noticeBytes.size)
                        thread(name = "Miauterm-rootfs-repair") {
                            try {
                                val repaired = runCatching { DebianRuntime.install(context) }.getOrDefault(false)
                                val repairedShell = DebianRuntime.shellPathForSession(root)
                                if (repaired && repairedShell != null && repairedShell.isFile && repairedShell.canExecute()) {
                                    DebianRuntime.recordRuntimeEvent(context, "Automatic Debian rootfs repair succeeded")
                                    // Wait for any in-flight start() to finish before retrying.
                                    while (starting.get()) Thread.sleep(50)
                                    if (!destroyed.get()) start()
                                } else {
                                    val detail = DebianRuntime.lastInstallError
                                        ?: "No executable shell after repair. ${DebianRuntime.shellProbeSummary(root)}"
                                    DebianRuntime.recordRuntimeEvent(context, "Automatic Debian rootfs repair failed: $detail")
                                    showStartupFailure("Automatic Debian repair failed: $detail\nOpen Settings > Linux system to retry.")
                                }
                            } finally {
                                rootfsRecoveryStarted.set(false)
                            }
                        }
                        return
                    }
                    val detail = if (DebianRuntime.isRemoved(context)) {
                        "The Debian system was removed. Restore it from Settings > Linux system."
                    } else {
                        "No valid executable ARM64 Debian shell was found under ${root.absolutePath}. " +
                            "Shell check: $probe"
                    }
                    throw IllegalStateException(detail)
                }

                stage = "preparing PRoot launcher"
                val prootShell = DebianRuntime.shellCommand(context)
                check(launcher.isFile && launcher.canExecute()) {
                    "PRoot launcher could not be created at ${launcher.absolutePath}."
                }
                val requestedDirectory = startDirectory
                    ?.takeIf { it.startsWith(root.absolutePath) }
                    ?.removePrefix(root.absolutePath)
                    ?.ifEmpty { account.home } ?: account.home
                val selectedHome = account.home.trimEnd('/').ifEmpty { "/" }
                val otherAccountHomes = DebianRuntime.accounts(root)
                    .asSequence()
                    .map { it.home.trimEnd('/').ifEmpty { "/" } }
                    .filter { it != selectedHome }
                val guestDirectory = if (otherAccountHomes.any { home ->
                        requestedDirectory == home || requestedDirectory.startsWith("$home/")
                    }
                ) account.home else requestedDirectory
                shell = prootShell
                args = DebianRuntime.shellArguments(context, guestDirectory, failsafeMode, account)
                ptyEnv = DebianRuntime.environment(context, failsafeMode, root, account)
                cwd = rootCwd
            }

            restoredOutput?.let { showRestoredOutput(it) }
            restoredOutput = null

            stage = "creating Android terminal PTY"
            ptyProcess = PtyProcess.create(
                cmd = shell,
                args = args,
                envVars = ptyEnv,
                cwd = cwd.absolutePath,
                rows = rows,
                cols = cols,
                widthPx = widthPx,
                heightPx = heightPx
            )
            if (destroyed.get()) {
                ptyProcess?.destroy()
                ptyProcess = null
                return
            }
            val process = ptyProcess
            if (process == null) {
                val message = "Android did not create a terminal PTY. Check Android logcat for MiautermPty errors."
                DebianRuntime.recordRuntimeEvent(context, "Session startup failed at $stage: $message")
                showStartupFailure(message)
                Log.e(tag, message)
                return
            }

            isRunning = true
            val startTime = System.currentTimeMillis()
            DebianRuntime.recordRuntimeEvent(context, "PTY created; pid=${process.pid}; launching PRoot")
            emulator.onTitleChange = { newTitle -> this.title = newTitle }
            emulator.onWorkingDirectory = { path ->
                if (guestWorkingDirectory != path) {
                    guestWorkingDirectory = path
                    onWorkingDirectoryChanged?.invoke()
                }
            }
            emulator.onClipboardSet = { text -> TerminalBridge.onClipboardRequest(context, text) }
            emulator.onCommandFinished = { durationMs, exitCode ->
                guestWorkingDirectory = null
                TerminalBridge.onCommandFinished(context, numberProvider?.invoke() ?: 1, durationMs, exitCode)
            }
            emulator.onSendResponse = { response -> write(response) }

            readerThread = thread(start = true, name = "Miauterm-PtyReader") {
                val buffer = ByteArray(4096)
                var emulatorFailures = 0
                try {
                    val stream = process.inputStream
                    while (isRunning) {
                        val read = try {
                            stream.read(buffer)
                        } catch (e: Exception) {
                            if (isRunning) Log.e(tag, "Error reading from PTY: ${e.message}", e)
                            break
                        }
                        if (read <= 0) break
                        try {
                            emulator.processInput(buffer, 0, read)
                            emulatorFailures = 0
                        } catch (e: Exception) {
                            emulatorFailures++
                            Log.e(tag, "Emulator could not process PTY output", e)
                            if (emulatorFailures >= 3) {
                                val msg = "\r\n[Miauterm could not render Linux output - see diagnostic log]\r\n"
                                val bytes = msg.toByteArray(Charsets.UTF_8)
                                runCatching { emulator.processInput(bytes, 0, bytes.size) }
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isRunning) Log.e(tag, "Error in PTY reader: ${e.message}", e)
                } finally {
                    val code = try { process.waitFor() } catch (e: Exception) {
                        Log.w(tag, "Could not wait for the terminal process: ${e.message}")
                        -1
                    }
                    exitCode = code
                    val duration = System.currentTimeMillis() - startTime
                    DebianRuntime.recordRuntimeEvent(context, "Terminal process exited; code=$code durationMs=$duration")
                    Log.i(tag, "Process exited with code $code after ${duration}ms")
                    isRunning = false
                    if (code != 0) {
                        val msg = "\r\n[Linux session exited with code $code - see Settings > Debian system > diagnostic log]\r\n"
                        val bytes = msg.toByteArray(Charsets.UTF_8)
                        runCatching { emulator.processInput(bytes, 0, bytes.size) }
                    } else if (duration <= SessionManager.SESSION_START_GRACE_MS) {
                        val msg = "\r\n[Debian shell exited immediately - see Settings > Debian system > diagnostic log]\r\n"
                        val bytes = msg.toByteArray(Charsets.UTF_8)
                        runCatching { emulator.processInput(bytes, 0, bytes.size) }
                    }
                    onSessionFinished?.invoke(code)
                }
            }
        } catch (t: Throwable) {
            if (readerThread == null) {
                ptyProcess?.destroy()
                ptyProcess = null
            }
            val detail = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
            val message = "Failed while $stage: $detail"
            DebianRuntime.recordRuntimeEvent(context, "Session startup failed at $stage: $detail")
            Log.e(tag, message, t)
            showStartupFailure(message)
        } finally {
            starting.set(false)
        }
    }

    private fun showStartupFailure(message: String) {
        val output = "\r\n\u001b[31mMiauterm could not start the Linux system\u001b[0m\r\n$message\r\n"
        val bytes = output.toByteArray(Charsets.UTF_8)
        emulator.processInput(bytes, 0, bytes.size)
    }

    private fun showRestoredOutput(output: String) {
        val sb = StringBuilder()
        if (output.isNotEmpty()) {
            sb.append("\u001b[2m").append(output.replace("\n", "\r\n")).append("\u001b[0m\r\n")
        }
        sb.append("\u001b[2m── ").append(context.getString(R.string.session_restored)).append(" ──\u001b[0m\r\n")
        val bytes = sb.toString().toByteArray(Charsets.UTF_8)
        emulator.processInput(bytes, 0, bytes.size)
    }

    private val writeExecutor: java.util.concurrent.ExecutorService = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.LinkedBlockingQueue<Runnable>(1024),
        { runnable -> Thread(runnable, "Miauterm-PtyWriter").apply { isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()
    )

    fun write(bytes: ByteArray) {
        if (!isRunning || writeExecutor.isShutdown) return
        try {
            writeExecutor.execute {
                if (!isRunning) return@execute
                try {
                    ptyProcess?.outputStream?.let { os ->
                        os.write(bytes)
                        os.flush()
                    }
                } catch (e: IOException) {
                    if (isRunning) {
                        Log.e(tag, "Error writing to PTY: ${e.message}")
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {

        }
    }

    fun write(text: String) {
        write(text.toByteArray(Charsets.UTF_8))
    }

    private var isFocused = false

    fun setFocused(focused: Boolean) {
        if (focused == isFocused) return
        isFocused = focused
        emulator.focusReport(focused)?.let { write(it) }
    }

    fun updateDimensions(newRows: Int, newCols: Int, newWidthPx: Int, newHeightPx: Int) {
        if (newRows <= 0 || newCols <= 0) return
        this.rows = newRows
        this.cols = newCols
        this.widthPx = newWidthPx
        this.heightPx = newHeightPx

        emulator.resize(newRows, newCols)
        ptyProcess?.resize(newRows, newCols, newWidthPx, newHeightPx)
    }

    fun awaitProcessExit(timeoutMs: Long) {
        try { readerThread?.join(timeoutMs) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    fun destroy() {
        runCatching { destroyed.set(true) }
        isRunning = false
        writeExecutor.shutdownNow()
        ptyProcess?.destroy()
        try {
            readerThread?.interrupt()
            readerThread?.join(1000)
        } catch (_: Exception) {}
    }
}

internal fun failsafeEnvironment(home: File, cache: File): Array<String> = arrayOf(
    "HOME=${home.absolutePath}",
    "PWD=${home.absolutePath}",
    "PS1=${home.absolutePath} \$ ",
    "PATH=/system/bin:/system/xbin",
    "TERM=xterm-256color",
    "COLORTERM=truecolor",
    "LANG=C.UTF-8",
    "LC_ALL=C.UTF-8",
    "TMPDIR=${cache.absolutePath}"
)
