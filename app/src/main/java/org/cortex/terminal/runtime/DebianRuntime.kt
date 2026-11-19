package org.cortex.terminal.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Environment as AndroidEnvironment
import androidx.preference.PreferenceManager
import android.util.Log
import org.cortex.terminal.BuildConfig
import org.cortex.terminal.api.ApiServer
import org.cortex.terminal.pty.PtyNative
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap

object DebianRuntime {
    private const val TAG = "DebianRuntime"
    private const val RESOLV_CONF_HEADER = "# Managed by Miauterm. Android DNS with public fallback.\n"
    private const val CUSTOM_RESOLV_CONF_HEADER = "# Managed by Miauterm. Custom DNS.\n"
    const val PREF_CUSTOM_DNS_SERVERS = "custom_dns_servers"
    const val PREF_CUSTOM_HOSTNAME = "custom_hostname"
    private const val HOSTS_START = "# BEGIN Miauterm Debian repository addresses"
    private const val HOSTS_END = "# END Miauterm Debian repository addresses"
    private val FALLBACK_DNS_SERVERS = listOf("1.1.1.1", "8.8.8.8")
    private val PSEUDO_FILESYSTEMS = listOf("/dev", "/proc", "/sys")
    private val DNS_OVER_HTTPS_ENDPOINTS = listOf(
        "https://1.1.1.1/dns-query",
        "https://8.8.8.8/resolve"
    )
    private val networkMaintenance = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Miauterm-NetworkSetup").apply { isDaemon = true }
    }
    private val pendingHostRefreshes = ConcurrentHashMap.newKeySet<String>()
    private const val VERSION = 19
    private const val ROOTFS_ASSET = "debian-arm64.tar.zst"
    private const val PROOT_ASSET_DIR = "proot/arm64-v8a"
    private const val PORT_REMAP_ASSET = "libmiauterm-portmap.so"
    private const val PORT_REMAP_GUEST_PATH = "/usr/lib/miauterm/libportmap.so"
    private val prootFiles = listOf("proot", "loader", "libtalloc.so.2", "libandroid-shmem.so")
    private val essentialCommands = listOf(
        "usr/bin/passwd",
        "usr/sbin/useradd",
        "usr/sbin/groupadd",
        "usr/sbin/userdel",
        "usr/sbin/usermod",
        "usr/bin/chown",
        "usr/bin/dpkg",
        "usr/bin/apt",
        "usr/bin/apt-get"
    )
    private val networkCommands = listOf(
        "usr/bin/ping",
        "usr/sbin/ip",
        "usr/bin/ss",
        "usr/sbin/ifconfig",
        "usr/sbin/route",
        "usr/bin/netstat",
        "usr/sbin/arp",
        "usr/bin/curl",
        "usr/bin/wget"
    )
    private val networkPackages = listOf("iputils-ping", "iproute2", "net-tools", "curl", "wget")
    const val PREF_PROOT_DIAGNOSTICS = "proot_diagnostics"
    const val PREF_ROOTFS_REMOVED = "rootfs_removed"
    @Volatile
    var lastInstallError: String? = null
        private set
    @Volatile
    var reinstallConfirmation: (() -> Boolean)? = null
    private const val LOG_LIMIT = 2 * 1024 * 1024L

    fun isRemoved(context: Context): Boolean = PreferenceManager.getDefaultSharedPreferences(context)
        .getBoolean(PREF_ROOTFS_REMOVED, false)

    private fun expectedVersionMarker(): String = "$VERSION\n${BuildConfig.ROOTFS_SHA256}\n"

    private fun readVersionMarker(root: File): String? = runCatching {
        val marker = File(root, ".miauterm-version")
        if (!marker.isFile || ElfLinkerPatcher.isSymlink(marker) || marker.length() > 128) return@runCatching null
        marker.readText().trim()
    }.getOrNull()

    fun isInstalled(context: Context): Boolean {
        if (isRemoved(context)) return false
        val root = Environment.getCortexRoot(context)
        return runCatching {
            readVersionMarker(root) == expectedVersionMarker().trim() &&
                File(root, "root").isDirectory && systemName(root) != null && shellPath(root) != null &&
                hasDebianUsrMergeLinks(root) &&
                invalidEssentialCommand(root) == null &&
                MiautermCommandInstaller.isInstalled(root) &&
                prootFiles.all { File(context.filesDir, "proot/$it").isFile } &&
                File(context.filesDir, "proot/proot").canExecute()
        }.getOrDefault(false)
    }

    @Synchronized
    fun install(context: Context, onProgress: ((Int) -> Unit)? = null, force: Boolean = false): Boolean {
        val installLock = FileLocks.tryLock(listOf(File(context.filesDir, ".debian-install.lock")))
        if (installLock == null) {
            lastInstallError = "Another Debian installation is already in progress"
            Log.w(TAG, lastInstallError.orEmpty())
            return false
        }
        return installLock.use { installRootfs(context, onProgress, force) }
    }

    private fun installRootfs(context: Context, onProgress: ((Int) -> Unit)?, force: Boolean): Boolean {
        val root = Environment.getCortexRoot(context)
        val runtime = File(context.filesDir, "proot")
        val archive = File(context.cacheDir, ROOTFS_ASSET)
        val staging = File(context.filesDir, "debian-install-staging")
        val previous = File(context.filesDir, "debian-install-previous")
        val failed = File(context.filesDir, "debian-install-failed")
        val restorePrevious = File(context.filesDir, "debian-restore-previous")
        return try {
            onProgress?.invoke(2)
            installProot(context, runtime)
            if (!root.exists()) {
                listOf(restorePrevious, previous).firstOrNull { it.isDirectory }?.let { backup ->
                    if (!backup.renameTo(root)) {
                        Log.w(TAG, "Could not restore the previous Linux rootfs")
                    }
                }
            }
            if (!force && root.exists() && (restorePrevious.isDirectory || previous.isDirectory)) {
                val currentWorks = runCatching {
                    verifyRootfs(root)
                    verifyProotStartsBash(context, root)
                }.isSuccess
                if (!currentWorks) {
                    val backup = listOf(restorePrevious, previous).firstOrNull { it.isDirectory }
                    SafeDelete.deleteRecursively(failed)
                    if (backup != null && root.renameTo(failed) && backup.renameTo(root)) {
                        Log.w(TAG, "Restored the previous rootfs after verification failed")
                    } else if (!root.exists() && failed.isDirectory) {
                        failed.renameTo(root)
                    }
                }
            }

            if (root.exists()) {
                val existingMarker = readVersionMarker(root)
                val existingRootWorks = runCatching {
                    verifyRootfs(root)
                    prepareHome(root)
                    MiautermCommandInstaller.install(context, root)
                    verifyProotStartsBash(context, root)
                }.isSuccess
                if (existingRootWorks && existingMarker == expectedVersionMarker().trim()) {
                    onProgress?.invoke(92)
                    updateResolvConf(root, networkDnsServers(context), customDnsServers(context))
                    Thread {
                        runCatching { provisionOptionalNetworkTools(context, root) }
                    }.apply { isDaemon = true; start() }
                    File(context.filesDir, "rootfs-original.tar.zst").delete()
                    File(context.filesDir, "rootfs-original.installing").delete()
                    SafeDelete.deleteRecursively(previous)
                    SafeDelete.deleteRecursively(failed)
                    SafeDelete.deleteRecursively(restorePrevious)
                    PreferenceManager.getDefaultSharedPreferences(context).edit()
                        .remove(PREF_ROOTFS_REMOVED).commit()
                    lastInstallError = null
                    onProgress?.invoke(100)
                    return true
                }
                if (!force && existingRootWorks && reinstallConfirmation?.invoke() != true) {
                    lastInstallError = "Debian rootfs is from an older bundled version; confirm the upgrade to replace it."
                    Log.w(TAG, lastInstallError.orEmpty())
                    return false
                }
            }

            if (!SafeDelete.deleteRecursively(staging)) {
                val detail = SafeDelete.lastFailure?.let { ": $it" }.orEmpty()
                throw IllegalStateException("Could not clear the rootfs staging directory$detail")
            }
            staging.mkdirs()
            context.assets.open(ROOTFS_ASSET).use { input ->
                archive.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            onProgress?.invoke(12)
            val result = PtyNative.extractTar(archive.absolutePath, staging.absolutePath) { percent ->
                onProgress?.invoke(percent)
            }
            if (result != 0) throw IllegalStateException("Rootfs archive extraction failed ($result)")
            onProgress?.invoke(92)
            verifyRootfs(staging)
            ElfLinkerPatcher.patchAllDynamicLinkers(staging)
            ElfLinkerPatcher.ensureDynamicLinkerSymlinks(staging)
            ElfLinkerPatcher.fixAbsoluteSymlinks(staging)
            prepareHome(staging)
            MiautermCommandInstaller.install(context, staging)
            updateResolvConf(staging, networkDnsServers(context), customDnsServers(context))
            Thread {
                runCatching { provisionOptionalNetworkTools(context, staging) }
            }.apply { isDaemon = true; start() }
            verifyProotStartsBash(context, staging)
            FileInstaller.install(File(staging, ".miauterm-version"), expectedVersionMarker(), 420)

            if (!SafeDelete.deleteRecursively(previous)) {
                throw IllegalStateException("Could not clear the previous rootfs backup")
            }
            if (root.exists() && !root.renameTo(previous)) {
                throw IllegalStateException("Could not move the current Linux rootfs")
            }
            if (!staging.renameTo(root)) {
                if (!previous.renameTo(root)) {
                    Log.e(TAG, "Old rootfs remains at ${previous.absolutePath}")
                }
                throw IllegalStateException("Could not install the verified rootfs")
            }
            verifyRootfs(root)
            verifyProotStartsBash(context, root)
            if (!SafeDelete.deleteRecursively(previous)) {
                Log.w(TAG, "Could not remove the previous rootfs backup")
            }
            SafeDelete.deleteRecursively(failed)
            SafeDelete.deleteRecursively(restorePrevious)
            File(context.filesDir, "rootfs-original.tar.zst").delete()
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .remove(PREF_ROOTFS_REMOVED).commit()
            lastInstallError = null
            onProgress?.invoke(100)
            true
        } catch (e: Exception) {
            lastInstallError = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "Could not install the Linux rootfs", e)
            false
        } finally {
            archive.delete()
            SafeDelete.deleteRecursively(staging)
        }
    }

    fun removeDebian(context: Context) {
        val lock = FileLocks.tryLock(listOf(File(context.filesDir, ".debian-install.lock")))
            ?: error("Another Debian operation is already in progress")
        lock.use { removeDebianLocked(context) }
    }

    private fun removeDebianLocked(context: Context) {
        val root = Environment.getCortexRoot(context)
        val extras = listOf(
            root,
            File(context.filesDir, "debian-reset-staging"),
            File(context.filesDir, "debian-install-staging"),
            File(context.filesDir, "debian-install-previous"),
            File(context.filesDir, "debian-install-failed"),
            File(context.filesDir, "debian-restore-staging"),
            File(context.filesDir, "debian-restore-previous"),
            File(context.filesDir, "debian-restore-failed"),
            File(context.filesDir, "rootfs-original.tar.zst"),
            File(context.filesDir, "rootfs-original.installing")
        )
        extras.forEach { path ->
            if (!SafeDelete.deleteRecursively(path)) error("Could not remove ${path.name}")
        }
        File(context.filesDir, "proot/proot.log").delete()
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(PREF_ROOTFS_REMOVED, true).commit()
    }

    internal fun prepareHome(root: File) {
        val rootHome = File(root, "root")
        val legacyCortexConfig = File(rootHome, ".cortex")
        if (legacyCortexConfig.isDirectory) {
            val configDir = File(rootHome, ".config/miauterm")
            val known = legacyCortexConfig.listFiles().orEmpty()
            configDir.mkdirs()
            known.filter { it.isFile && it.name in setOf("keys", "keys.example", "font.ttf") }.forEach { old ->
                val target = File(configDir, old.name)
                if (!target.exists()) old.copyTo(target)
            }
            SafeDelete.deleteRecursively(legacyCortexConfig)
        }
        removeUnusedAndroidPlaceholders(rootHome)
        migrateManagedReadme(root, rootHome)

        val devDirectory = File(root, "dev")
        check(!ElfLinkerPatcher.isSymlink(devDirectory) && (devDirectory.isDirectory || devDirectory.mkdirs())) {
            "Could not create /dev in Debian"
        }
        val devPts = File(devDirectory, "pts")
        check(!ElfLinkerPatcher.isSymlink(devPts) && (devPts.isDirectory || devPts.mkdirs())) {
            "Could not create /dev/pts in Debian"
        }
        val ptmx = File(root, "dev/ptmx")
        if (!ptmx.exists()) check(ElfLinkerPatcher.createSymlink("pts/ptmx", ptmx)) {
            "Could not link /dev/ptmx to /dev/pts/ptmx"
        }

        val profileDirectory = File(root, "etc/profile.d")
        check(profileDirectory.isDirectory || profileDirectory.mkdirs()) {
            "Could not create the Linux login profile directory"
        }
        val aptConfigDirectory = File(root, "etc/apt/apt.conf.d")
        check(!ElfLinkerPatcher.isSymlink(aptConfigDirectory) &&
            (aptConfigDirectory.isDirectory || aptConfigDirectory.mkdirs())
        ) {
            "Could not create the Debian apt configuration directory"
        }
        val aptTerminalConfig = File(aptConfigDirectory, "99miauterm-no-pty")
        check(!ElfLinkerPatcher.isSymlink(aptTerminalConfig)) {
            "Debian apt terminal configuration must not be a symlink"
        }
        FileInstaller.install(
            aptTerminalConfig,
            "Dpkg::Use-Pty \"0\";\nDpkg::Progress-Fancy \"0\";\n",
            420
        )
        val loginDefs = File(root, "etc/login.defs")
        check(!ElfLinkerPatcher.isSymlink(loginDefs)) { "Debian /etc/login.defs must not be a symlink" }
        val loginDefsText = if (loginDefs.isFile) loginDefs.readText() else ""
        val createHome = Regex("(?m)^[ \\t]*CREATE_HOME[ \\t]+[^\\r\\n]*")
        val updatedLoginDefs = if (createHome.containsMatchIn(loginDefsText)) {
            createHome.replace(loginDefsText, "CREATE_HOME yes")
        } else {
            "${loginDefsText.trimEnd()}${if (loginDefsText.isBlank()) "" else "\n"}CREATE_HOME yes\n"
        }
        if (updatedLoginDefs != loginDefsText) {
            FileInstaller.install(loginDefs, updatedLoginDefs, 420)
        }
        val motd = File(root, "etc/motd")
        if (!ElfLinkerPatcher.isSymlink(motd)) {
            if (!motd.exists() || (motd.isFile && motd.length() == 0L)) {
                motd.writeText("Welcome to Debian in Miauterm.\n\n")
            }
        }
        val motdProfile = File(profileDirectory, "99-miauterm-motd.sh")
        FileInstaller.install(
            motdProfile,
            "case \$- in\n" +
                "  *i*)\n" +
                "    if [ -z \"\${MIAUTERM_MOTD_SHOWN:-}\" ]; then\n" +
                "      export MIAUTERM_MOTD_SHOWN=1\n" +
                "      [ ! -r /etc/motd ] || cat /etc/motd\n" +
                "    fi\n" +
                "    ;;\n" +
                "esac\n",
            420
        )
        val promptProfile = File(profileDirectory, "99-miauterm-prompt.sh")
        FileInstaller.install(
            promptProfile,
            "# Managed by Miauterm.\n" +
                "[ -n \"\${BASH_VERSION:-}\" ] || return\n" +
                "case \$- in *i*) ;; *) return ;; esac\n" +
                "PS1=\"\\[\\e[1;32m\\]\\u@\${MIAUTERM_HOSTNAME:-localhost}\\[\\e[0m\\] \\[\\e[34m\\]\\w\\[\\e[0m\\]\\$ \"\n",
            420
        )
        val colorsProfile = File(profileDirectory, "99-miauterm-colors.sh")
        FileInstaller.install(
            colorsProfile,
            "case \$- in *i*) ;; *) return ;; esac\n" +
                "if command -v dircolors >/dev/null 2>&1; then\n" +
                "  eval \"\$(dircolors -b)\"\n" +
                "  alias ls='ls --color=auto'\n" +
                "fi\n",
            420
        )
    }

    private fun removeUnusedAndroidPlaceholders(rootHome: File) {
        val androidDirectory = File(rootHome, "android")
        if (ElfLinkerPatcher.isSymlink(androidDirectory)) {
            if (!ElfLinkerPatcher.deleteIfExists(androidDirectory)) {
                Log.w(TAG, "Could not remove the unused Android storage link at ${androidDirectory.path}")
            }
            return
        }
        if (!androidDirectory.isDirectory) return
        val children = androidDirectory.listFiles() ?: return
        val placeholders = children.filter { child ->
            child.name in setOf("Download", "Documents") &&
                child.isDirectory &&
                !ElfLinkerPatcher.isSymlink(child) &&
                child.list()?.isEmpty() == true
        }
        val removed = placeholders.count { it.delete() }
        if (removed == placeholders.size && placeholders.size == children.size && placeholders.isNotEmpty()) {
            if (!androidDirectory.delete()) {
                Log.w(TAG, "Could not remove the unused Android storage directory at ${androidDirectory.path}")
            }
        }
    }

    private fun migrateManagedReadme(root: File, rootHome: File) {
        val previous = File(root, "README.md")
        val homeReadme = File(rootHome, "README.md")
        var destination = when {
            !homeReadme.exists() || isManagedReadme(homeReadme) -> homeReadme
            else -> File(rootHome, "Miauterm-README.md")
        }
        var suffix = 2
        while (destination.exists() && !isManagedReadme(destination)) {
            destination = File(rootHome, "Miauterm-README-$suffix.md")
            suffix++
        }
        runCatching {
            FileInstaller.install(destination, managedReadmeContents(), 420)
            if (isManagedReadme(previous) && !ElfLinkerPatcher.deleteIfExists(previous)) {
                Log.w(TAG, "Could not remove the old Miauterm README at ${previous.path}")
            }
        }.onFailure { Log.w(TAG, "Could not update the Miauterm README", it) }
    }

    private fun isManagedReadme(file: File): Boolean = runCatching {
        if (!file.isFile || ElfLinkerPatcher.isSymlink(file)) return@runCatching false
        file.inputStream().use { input ->
            val header = ByteArray(64)
            val count = input.read(header)
            count > 0 && String(header, 0, count, Charsets.UTF_8).startsWith("# Miauterm Linux system")
        }
    }.getOrDefault(false)

    private fun managedReadmeContents(): String =
        "# Miauterm Linux system\n\n" +
            "This Debian system is launched by Miauterm through PRoot. No separate terminal app is required.\n\n" +
            "## Shared folders\n\n" +
            "`~/android` is created only after Download and Documents access is enabled and permission is granted in Miauterm Settings. New Linux sessions bind only those two shared folders when enabled. PRoot also exposes Android `/dev`, `/proc`, and `/sys` pseudo-filesystems for terminal devices, process data, and hardware reporting. Debian's other directories remain in the app-private root filesystem; Android `/system`, `/data`, and general shared storage are not bound. Changing shared-folder access does not alter running sessions.\n\n" +
            "## Terminal startup\n\n" +
            "The MOTD is shown when an interactive shell starts. The prompt shows the current directory in blue, using `~` at home and paths such as `~/projects` as you move into folders. The account and configured Debian hostname appear in bold green.\n\n" +
            "## Extra keys\n\n" +
            "Customize keys in `~/.config/miauterm/keys` using nested arrays:\n\n" +
            "```text\n[\n  [\"ESC\", \"/\", \"-\", \"HOME\", \"UP\", \"END\", \"PGUP\"],\n  [\"TAB\", \"CTRL\", \"ALT\", \"LEFT\", \"DOWN\", \"RIGHT\", \"PGDN\"]\n]\n```\n\n" +
            "Select extra-key buttons in Miauterm Settings. Edit the Termux-style nested-array layout from Debian at `~/.config/miauterm/keys` with a text editor, then run `miauterm-cmd run keysreload`.\n\n" +
            "Use Debian's `su` command to switch accounts inside the current shell. It is provided by Debian's `util-linux` package and follows Debian's normal account and password rules. The `root` identity remains simulated by PRoot and does not grant Android root access.\n\n" +
            "Miauterm checks Debian usr-merge links before startup: `/bin -> usr/bin`, `/sbin -> usr/sbin`, and `/lib -> usr/lib`. Matching files are merged; conflicting files stop repair without being overwritten. The bundled and validated rootfs is Debian ARM64. Other distributions are not supported by Debian startup checks.\n\n" +
            "Use Miauterm Settings to view or clear PRoot diagnostics, open a failsafe session, read the rootfs and symlink guide, back up or restore files, or reset the bundled Debian system. Export a backup before replacing files if you need to keep them.\n\n" +
            "## Network settings\n\n" +
            "Set custom DNS servers and a Debian hostname from Miauterm Settings > Network. DNS accepts up to three IPv4 or IPv6 addresses separated by commas. Leave it blank to use Android-provided DNS with public fallbacks. The hostname sets the shell prompt, `\$HOSTNAME`, `\$MIAUTERM_HOSTNAME`, `/etc/hostname`, and the guest results of `gethostname()` and `uname()` used by Fastfetch. Android's kernel hostname is unchanged. Debian process and hardware reporting use the bound `/proc` and `/sys` views.\n\n" +
            "Miauterm maps guest server binds from port 80 to 2080 and 443 to 2443. Loopback clients use the same mapping; connections to remote hosts keep their original ports. Guest services listen on the mapped high ports, which are also shown in local server notifications. For outgoing SSH, Miauterm attempts to install Debian's `openssh-client` during rootfs setup when network access is available. If `ssh` is still missing, run `apt install openssh-client`, then connect with `ssh user@host`. Miauterm does not start an SSH server.\n\n" +
            "Miauterm checks GitHub Releases only when you tap Settings > Updates & Support > Check for updates. If a newer release exists, Android shows a notification that opens the release page in your browser; APK download and installation remain manual.\n\n" +
            "This Miauterm version is 0.1.3-coral, with Coral as its release codename.\n\n" +
            "The `root` shell identity is simulated by PRoot; it is not Android root. Linux account names do not create separate Android security identities. Miauterm does not start a privileged chroot session.\n"

    internal fun prepareRestoredRootfs(context: Context, root: File) {
        verifyRootfs(root)
        prepareHome(root)
        MiautermCommandInstaller.install(context, root)
        installProot(context, File(context.filesDir, "proot"))
        verifyProotStartsBash(context, root)
        FileInstaller.install(File(root, ".miauterm-version"), expectedVersionMarker(), 420)
    }

    private fun verifyProotStartsBash(context: Context, root: File) {
        val launcher = shellCommand(context)
        val shell = shellPath(root)
            ?: throw IllegalStateException(
                "Cannot verify PRoot startup: no executable shell found in ${root.absolutePath}"
            )
        val args = arrayOf(
            "--link2symlink", "--kill-on-exit", "--rootfs=${root.absolutePath}",
            "--root-id", "--pwd=/root",
            "/" + shell.relativeTo(root).invariantSeparatorsPath,
            "-c",
            "/usr/sbin/useradd --help >/dev/null && " +
                "/usr/sbin/groupadd --help >/dev/null && " +
                "/usr/bin/passwd --help >/dev/null && " +
                "/usr/bin/chown --version >/dev/null && " +
                "/usr/bin/dpkg --version >/dev/null && " +
                "/usr/bin/apt --version >/dev/null && " +
                "/usr/bin/apt-get --version >/dev/null"
        )
        val process = ProcessBuilder(listOf(launcher) + args.toList())
            .directory(File(root, "root"))
            .redirectErrorStream(true)
            .apply {
                this.environment().apply {
                    clear()
                    DebianRuntime.environment(context, failsafe = true, rootfs = root).forEach { entry ->
                        val separator = entry.indexOf("=")
                        if (separator > 0) put(entry.substring(0, separator), entry.substring(separator + 1))
                    }
                }
            }
            .start()
        val (output, exitCode) = runProcess(process, 120_000)
        check(exitCode == 0) {
            output.ifEmpty {
                "PRoot launcher could not start ${shellPath(root)} from ${root.absolutePath} (exit $exitCode)"
            }
        }
    }

    private fun runProcess(process: Process, timeoutMs: Long): Pair<String, Int> {
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val watchdog = Thread {
            try {
                Thread.sleep(timeoutMs)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (runCatching { process.exitValue() }.isFailure) {
                timedOut.set(true)
                runCatching { process.destroy() }
            }
        }
        watchdog.isDaemon = true
        watchdog.start()
        val output = StringBuilder()
        try {
            process.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    output.append(buffer, 0, count)
                    if (output.length > 4096) output.delete(0, output.length - 2048)
                }
            }
        } finally {
            watchdog.interrupt()
        }
        if (timedOut.get()) {
            process.destroy()
            throw IllegalStateException("Linux command timed out after ${timeoutMs / 1000} seconds")
        }
        return output.toString().trim() to process.waitFor()
    }

    private fun installProot(context: Context, target: File) {
        if (!target.exists()) target.mkdirs()
        prootFiles.forEach { name ->
            val file = File(target, name)
            val needsCopy = !file.isFile || file.length() == 0L ||
                ((name == "proot" || name == "loader") && !file.canExecute()) ||
                (name != "proot" && name != "loader" && !file.canRead())
            if (needsCopy) {
                val temporary = File(target, "$name.installing")
                try {
                    if (temporary.exists() && !temporary.delete()) {
                        throw IllegalStateException("Could not clear PRoot runtime file: $name")
                    }
                    context.assets.open("$PROOT_ASSET_DIR/$name").use { input ->
                        java.io.FileOutputStream(temporary).use { output ->
                            input.copyTo(output)
                            output.fd.sync()
                        }
                    }
                    if (!ElfLinkerPatcher.atomicReplace(temporary, file)) {
                        throw IllegalStateException("Could not install PRoot runtime file: $name")
                    }
                } finally {
                    temporary.delete()
                }
            }
            if (name == "proot" || name == "loader") {
                check(file.setExecutable(true, true) || file.canExecute()) { "Cannot execute PRoot runtime file: $name" }
            } else {
                check(file.setReadable(true, true) || file.canRead()) { "Cannot read PRoot runtime file: $name" }
            }
        }
    }

    internal fun verifyRootfs(root: File) {
        validateDebianRootfsIdentity(root)
        repairDebianUsrMergeLinks(root)
        val shell = shellPath(root)
            ?: throw IllegalStateException(
                "Debian has no valid ARM64 bash or sh at /usr/bin/bash, /bin/bash, /usr/bin/sh, or /bin/sh."
            )
        check(isArm64Elf(shell)) {
            "The Debian shell is not a little-endian ARM64 executable"
        }
        val invalidCommand = invalidEssentialCommand(root)
        check(invalidCommand == null) {
            "Debian command /$invalidCommand is missing, truncated, or not an executable ARM64 binary."
        }
        check(File(root, "root").isDirectory || File(root, "root").mkdirs()) {
            "Could not prepare /root in Debian"
        }
    }

    internal fun repairUsrMergeLinks(root: File) {
        validateDebianRootfsIdentity(root)
        repairDebianUsrMergeLinks(root)
        runCatching { ElfLinkerPatcher.fixAbsoluteSymlinks(root) }
            .onFailure { Log.w(TAG, "Could not repair absolute symlinks", it) }
    }

    private fun validateDebianRootfsIdentity(root: File) {
        val osRelease = File(root, "etc/os-release")
        if (!osRelease.isFile || osRelease.readLines().none { it.trim() == "ID=debian" } ||
            !File(root, "etc/debian_version").isFile
        ) {
            throw IllegalStateException("The root filesystem is not a supported Debian installation.")
        }
    }

    private fun invalidCommand(root: File, paths: List<String>): String? =
        paths.firstOrNull { path ->
            val command = File(root, path)
            !command.isFile || command.length() < 4096L ||
                !command.canExecute() || !isArm64Elf(command)
        }

    private fun invalidEssentialCommand(root: File): String? = invalidCommand(root, essentialCommands)

    private fun repairDebianUsrMergeLinks(root: File) {
        for ((name, target) in listOf("bin" to "usr/bin", "sbin" to "usr/sbin", "lib" to "usr/lib")) {
            val link = File(root, name)
            val targetDir = File(root, target)
            if (!targetDir.exists() && !ElfLinkerPatcher.isSymlink(targetDir)) {
                check(targetDir.mkdirs()) { "Could not create Debian /$target directory" }
            }
            check(targetDir.isDirectory && !ElfLinkerPatcher.isSymlink(targetDir)) {
                "Debian /$target directory is missing or invalid"
            }
            if (ElfLinkerPatcher.readSymlink(link) == target) continue
            if (ElfLinkerPatcher.isSymlink(link)) {
                check(ElfLinkerPatcher.deleteIfExists(link)) { "Could not remove invalid Debian /$name symlink" }
            } else if (link.exists()) {
                check(link.isDirectory) { "Debian /$name is not a directory or symlink" }
                mergeUsrMergeDirectory(link, targetDir)
                check(link.delete()) { "Could not remove Debian /$name directory after migration" }
            }
            check(ElfLinkerPatcher.createSymlink(target, link)) { "Could not create Debian /$name -> $target symlink" }
            check(isUsrMergeLink(root, name, target)) {
                "Could not restore Debian /$name -> $target symlink"
            }
        }
    }

    private fun hasDebianUsrMergeLinks(root: File): Boolean =
        listOf("bin" to "usr/bin", "sbin" to "usr/sbin", "lib" to "usr/lib").all { (name, target) ->
            isUsrMergeLink(root, name, target)
        }

    private fun isUsrMergeLink(root: File, name: String, target: String): Boolean {
        val link = File(root, name)
        val targetDir = File(root, target)
        return ElfLinkerPatcher.isSymlink(link) && targetDir.isDirectory &&
            runCatching { link.canonicalFile == targetDir.canonicalFile }.getOrDefault(false)
    }

    private fun mergeUsrMergeDirectory(source: File, destination: File) {
        val children = source.listFiles()
            ?: throw IllegalStateException("Could not read ${source.absolutePath}")
        for (child in children) {
            val target = File(destination, child.name)
            if (!target.exists() && !ElfLinkerPatcher.isSymlink(target)) {
                check(child.renameTo(target)) { "Could not migrate ${child.absolutePath} into ${target.absolutePath}" }
                continue
            }
            if (!ElfLinkerPatcher.isSymlink(child) && child.isDirectory &&
                !ElfLinkerPatcher.isSymlink(target) && target.isDirectory
            ) {
                mergeUsrMergeDirectory(child, target)
                check(child.delete()) { "Could not remove merged directory ${child.absolutePath}" }
                continue
            }
            val duplicate = when {
                ElfLinkerPatcher.isSymlink(child) && ElfLinkerPatcher.isSymlink(target) ->
                    ElfLinkerPatcher.readSymlink(child) == ElfLinkerPatcher.readSymlink(target)
                !ElfLinkerPatcher.isSymlink(child) && !ElfLinkerPatcher.isSymlink(target) &&
                    child.isFile && target.isFile -> filesMatch(child, target)
                else -> false
            }
            check(duplicate) { "Conflicting Debian files at ${child.absolutePath} and ${target.absolutePath}" }
            check(ElfLinkerPatcher.deleteIfExists(child)) { "Could not remove duplicate ${child.absolutePath}" }
        }
    }

    private fun filesMatch(first: File, second: File): Boolean {
        if (first.length() != second.length()) return false
        first.inputStream().buffered().use { left ->
            second.inputStream().buffered().use { right ->
                val leftBuffer = ByteArray(8192)
                val rightBuffer = ByteArray(8192)
                while (true) {
                    val leftCount = left.read(leftBuffer)
                    val rightCount = right.read(rightBuffer)
                    if (leftCount != rightCount) return false
                    if (leftCount < 0) return true
                    for (index in 0 until leftCount) {
                        if (leftBuffer[index] != rightBuffer[index]) return false
                    }
                }
            }
        }
    }

    internal fun shellPathForSession(root: File): File? = shellPath(root)

    internal fun shellProbeSummary(root: File): String =
        listOf("usr/bin/bash", "bin/bash", "usr/bin/sh", "bin/sh").joinToString("; ") { path ->
            val candidate = File(root, path)
            val symlink = ElfLinkerPatcher.readSymlink(candidate)
            "$path(exists=${candidate.exists()},file=${candidate.isFile},read=${candidate.canRead()}," +
                "exec=${candidate.canExecute()},arm64=${isArm64Elf(candidate)}," +
                "link=${symlink ?: "none"})"
        }

    private fun shellPath(root: File): File? {
        val candidates = listOf("usr/bin/bash", "bin/bash", "usr/bin/sh", "bin/sh")
            .map { File(root, it) }
        candidates.firstOrNull { it.isFile && it.canExecute() }?.let { return it }

        val repairable = candidates.firstOrNull { it.isFile && isArm64Elf(it) } ?: return null
        if (repairable.setExecutable(true, true) || repairable.canExecute()) {
            Log.w(TAG, "Restored execute permission on Linux shell ${repairable.absolutePath}")
            return repairable
        }
        Log.e(TAG, "Linux shell exists but cannot be made executable: ${repairable.absolutePath}")
        return null
    }

    private fun isArm64Elf(file: File): Boolean = runCatching {
        val header = ByteArray(20)
        file.inputStream().use { input ->
            var read = 0
            while (read < header.size) {
                val count = input.read(header, read, header.size - read)
                if (count < 0) break
                read += count
            }
            read == header.size && header[0] == 0x7f.toByte() &&
                header[1] == 'E'.code.toByte() && header[2] == 'L'.code.toByte() &&
                header[3] == 'F'.code.toByte() && header[4] == 2.toByte() &&
                header[5] == 1.toByte() && header[18] == 183.toByte() && header[19] == 0.toByte()
        }
    }.getOrDefault(false)

    fun systemName(context: Context): String? = systemName(Environment.getCortexRoot(context))

    private fun systemName(root: File): String? {
        val release = File(root, "etc/os-release").takeIf { it.isFile }?.readLines().orEmpty()
        val pretty = release.firstOrNull { it.startsWith("PRETTY_NAME=") }
            ?.substringAfter('=')?.trim()?.trim('\"', '\'')
        if (!pretty.isNullOrBlank()) return pretty
        return release.firstOrNull { it.startsWith("ID=") }?.substringAfter('=')?.trim()?.trim('\"', '\'')
            ?.takeIf { it.isNotBlank() }
    }

    @Synchronized
    fun shellCommand(context: Context): String {
        val prootDir = File(context.filesDir, "proot")
        val launcher = File(prootDir, "launcher.sh")
        val log = prootLog(context)
        val proot = File(prootDir, "proot").absolutePath
        val loader = File(prootDir, "loader").absolutePath
        val cache = File(context.cacheDir, "proot-tmp").absolutePath
        FileInstaller.install(
            launcher,
            "#!/system/bin/sh\n" +
                "LOG_FILE='${shellQuote(log.absolutePath)}'\n" +
                "if [ -f \"\$LOG_FILE\" ] && [ \"$(/system/bin/toybox wc -c < \"\$LOG_FILE\")\" -gt ${LOG_LIMIT} ]; then : > \"\$LOG_FILE\"; fi\n" +
                "export PROOT_LOADER='${shellQuote(loader)}'\n" +
                "export PROOT_TMP_DIR='${shellQuote(cache)}'\n" +
                "export LD_LIBRARY_PATH='${shellQuote(prootDir.absolutePath)}'\n" +
                "if [ \"\$MIAUTERM_PROOT_DIAGNOSTICS\" = 1 ]; then\n" +
                "  FIFO=\"\$PROOT_TMP_DIR/proot-log-\$\$\"\n" +
                "  if /system/bin/toybox mkfifo \"\$FIFO\" 2>/dev/null; then\n" +
                "    /system/bin/toybox tee -a \"\$LOG_FILE\" < \"\$FIFO\" >&2 & LOGGER=$!\n" +
                "    '${shellQuote(proot)}' -v 2 \"\$@\" 2>\"\$FIFO\"; STATUS=$?\n" +
                "    wait \$LOGGER; /system/bin/toybox rm -f \"\$FIFO\"; exit \$STATUS\n" +
                "  fi\n" +
                "  exec '${shellQuote(proot)}' -v 2 \"\$@\"\n" +
                "fi\n" +
                "exec '${shellQuote(proot)}' \"\$@\"\n",
            493
        )
        return launcher.absolutePath
    }

    private fun shellQuote(value: String): String = value.replace("'", "'\\''")

    fun prootLog(context: Context): File = File(context.filesDir, "proot/proot.log")

    fun recordRuntimeEvent(context: Context, message: String) {
        if (!diagnosticsEnabled(context)) return
        runCatching {
            val log = prootLog(context)
            log.parentFile?.mkdirs()
            synchronized(DebianRuntime::class.java) {
                if (log.length() > LOG_LIMIT) log.writeText("")
                val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date())
                log.appendText("[$timestamp] $message\n")
            }
        }.onFailure { Log.w(TAG, "Could not write startup diagnostic", it) }
    }

    fun diagnosticsEnabled(context: Context): Boolean = PreferenceManager
        .getDefaultSharedPreferences(context).getBoolean(PREF_PROOT_DIAGNOSTICS, false)

    fun clearProotLog(context: Context): Boolean {
        val log = prootLog(context)
        return !log.exists() || log.delete()
    }

    private val pendingResolverUpdates = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var lastResolverSummary: String? = null

    fun shellArguments(context: Context, guestDirectory: String?, failsafe: Boolean = false, accountOverride: LinuxAccount? = null): Array<String> {
        val rootfs = Environment.getCortexRoot(context)
        MiautermCommandInstaller.removeLegacySu(rootfs)
        migrateManagedReadme(rootfs, File(rootfs, "root"))
        updateHostnameFile(rootfs, configuredHostname(context))
        updateAndroidHostFile(rootfs, context)
        updateResolvConf(rootfs, networkDnsServers(context), customDnsServers(context))
        scheduleResolverUpdate(context, rootfs)
        lastResolverSummary?.let { recordRuntimeEvent(context, it) }
        val root = rootfs.absolutePath
        val account = accountOverride ?: selectedAccount(context, rootfs)
        val args = mutableListOf("--link2symlink", "--kill-on-exit", "--rootfs=$root")
        if (account.uid == 0) args += "--root-id" else args += "--change-id=${account.uid}:${account.gid}"
        args += "--pwd=${guestDirectory ?: account.home}"
        appendPseudoFilesystemBinds(args, rootfs)
        recordRuntimeEvent(context, "Mounted Android pseudo filesystems: ${PSEUDO_FILESYSTEMS.joinToString()}")
        if (ApiServer.connectionFile(context).isFile) {
            val connection = ApiServer.connectionDirectory(context)
            val target = File(rootfs, "run/miauterm-cmd")
            check(!ElfLinkerPatcher.isSymlink(target)) {
                "Debian /run/miauterm-cmd must not be a symlink"
            }

            if (target.exists() && !target.isDirectory) {
                check(target.delete()) { "Could not replace Debian /run/miauterm-cmd" }
            }
            check(target.isDirectory || target.mkdirs()) {
                "Could not create Debian /run/miauterm-cmd"
            }
            args += "--bind=${connection.absolutePath}:/run/miauterm-cmd"
        }
        val accountHome = File(rootfs, account.home.removePrefix("/"))
        val androidDirectory = File(accountHome, "android")
        if (!failsafe && isSharedFolderAccessEnabled(context) &&
            !ElfLinkerPatcher.isSymlink(accountHome) &&
            !ElfLinkerPatcher.isSymlink(androidDirectory) &&
            (androidDirectory.isDirectory || androidDirectory.mkdirs())
        ) {
            publicDirectory(context, AndroidEnvironment.DIRECTORY_DOWNLOADS)?.let { host ->
                val guest = File(androidDirectory, "Download")
                if (!ElfLinkerPatcher.isSymlink(guest) && (guest.isDirectory || guest.mkdirs())) {
                    args += "--bind=${host.absolutePath}:${account.home.trimEnd('/')}/android/Download"
                }
            }
            publicDirectory(context, "Documents")?.let { host ->
                val guest = File(androidDirectory, "Documents")
                if (!ElfLinkerPatcher.isSymlink(guest) && (guest.isDirectory || guest.mkdirs())) {
                    args += "--bind=${host.absolutePath}:${account.home.trimEnd('/')}/android/Documents"
                }
            }
        }
        val shell = account.shell.takeIf { it.startsWith("/") && File(rootfs, it.removePrefix("/")).canExecute() }
            ?: shellPath(rootfs)?.let { "/" + it.relativeTo(rootfs).invariantSeparatorsPath }
            ?: "/bin/sh"
        if (installPortRemapLibrary(context, rootfs)) {
            args += listOf(
                "/usr/bin/env",
                "LD_PRELOAD=$PORT_REMAP_GUEST_PATH",
                "MIAUTERM_PORT_REMAP=80:2080,443:2443",
                shell,
                "-l",
                "-i"
            )
        } else {
            args += listOf(shell, "-l", "-i")
        }
        return args.toTypedArray()
    }

    private fun installPortRemapLibrary(context: Context, root: File): Boolean = runCatching {
        val rootPath = root.canonicalFile
        val env = File(rootPath, "usr/bin/env")
        check(env.isFile && env.canExecute() && isArm64Elf(env)) {
            "Debian /usr/bin/env is unavailable; guest port mapping is disabled"
        }
        val library = File(rootPath, PORT_REMAP_GUEST_PATH.removePrefix("/"))
        var parent = library.parentFile ?: error("Invalid port map library path")
        while (parent != rootPath && parent != null) {
            check(!ElfLinkerPatcher.isSymlink(parent)) { "Port map directory is a symlink: ${parent.path}" }
            check(parent.canonicalFile.path.startsWith(rootPath.path + File.separator)) {
                "Port map directory leaves the Debian root filesystem"
            }
            parent = parent.parentFile
        }
        val bytes = context.assets.open(PORT_REMAP_ASSET).use { it.readBytes() }
        FileInstaller.install(library, bytes, 420)
        check(FileInstaller.holds(library, bytes)) { "Port map library verification failed" }
        recordRuntimeEvent(context, "Guest ports 80 and 443 are mapped to 2080 and 2443")
        true
    }.onFailure { error ->
        Log.w(TAG, "Guest port mapping is unavailable", error)
        recordRuntimeEvent(context, "Guest port mapping unavailable: ${error.message ?: error.javaClass.simpleName}")
    }.getOrDefault(false)


    private fun appendPseudoFilesystemBinds(args: MutableList<String>, rootfs: File) {
        PSEUDO_FILESYSTEMS.forEach { mountPath ->
            val host = File(mountPath)
            check(host.isDirectory) { "Android $mountPath is unavailable for the Linux session" }

            val guest = File(rootfs, mountPath.removePrefix("/"))
            check(!ElfLinkerPatcher.isSymlink(guest)) {
                "Debian $mountPath must be a directory, not a symlink"
            }
            check(guest.isDirectory || guest.mkdirs()) {
                "Could not prepare Debian $mountPath mount point"
            }
            Log.i(TAG, "Binding Android pseudo filesystem $mountPath")
            args += "--bind=${host.absolutePath}:$mountPath"
        }
    }

    private fun scheduleResolverUpdate(context: Context, root: File) {
        val key = root.absolutePath
        if (!pendingResolverUpdates.add(key)) return
        networkMaintenance.execute {
            try {
                val dnsServers = networkDnsServers(context)
                val customDns = customDnsServers(context)
                updateResolvConf(root, dnsServers, customDns)
                val source = when {
                    customDns.isNotEmpty() -> "custom DNS"
                    dnsServers.isEmpty() -> "fallback DNS"
                    else -> "Android DNS with fallback"
                }
                lastResolverSummary = "Debian resolver uses $source: ${resolverAddresses(dnsServers, customDns).joinToString()}"
                recordRuntimeEvent(context, lastResolverSummary.orEmpty())
            } catch (error: Exception) {
                Log.w(TAG, "Could not update Debian DNS resolver", error)
                recordRuntimeEvent(context, "Debian resolver update failed: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                pendingResolverUpdates.remove(key)
            }
        }
        refreshRepositoryHostsAsync(context, root)
    }

    private fun refreshRepositoryHostsAsync(context: Context, root: File) {
        val key = root.absolutePath
        if (!pendingHostRefreshes.add(key)) return
        networkMaintenance.execute {
            try {
                val mappings = refreshRepositoryHosts(root)
                recordRuntimeEvent(
                    context,
                    "Debian apt repository host mappings: ${mappings.joinToString { "${it.second}=${it.first}" }.ifEmpty { "none resolved" }}"
                )
            } catch (error: Exception) {
                Log.w(TAG, "Could not refresh Debian repository host mappings", error)
                recordRuntimeEvent(context, "Debian repository host refresh failed: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                pendingHostRefreshes.remove(key)
            }
        }
    }

    internal fun refreshRepositoryHosts(root: File): List<Pair<String, String>> {
        val apt = File(root, "etc/apt")
        val sourceFiles = buildList {
            add(File(apt, "sources.list").takeIf { it.isFile })
            File(apt, "sources.list.d").listFiles()
                ?.filter { it.isFile && (it.extension == "list" || it.extension == "sources") }
                ?.forEach(::add)
        }.filterNotNull()
        val urlPattern = Regex("https?://[^\\s,]+", RegexOption.IGNORE_CASE)
        val hosts = sourceFiles.asSequence()
            .flatMap { file -> runCatching { file.readLines() }.getOrDefault(emptyList()).asSequence() }
            .filterNot { it.trimStart().startsWith('#') }
            .flatMap { line -> urlPattern.findAll(line).map { it.value.trimEnd('.', ';') } }
            .mapNotNull { url -> runCatching { URI(url).host?.lowercase() }.getOrNull() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

        val mappings = hosts.flatMap { host ->
            resolveRepositoryHost(host).map { (it.hostAddress ?: it.toString()) to host }
        }.distinct()
        val hostsFile = File(root, "etc/hosts")
        check(!ElfLinkerPatcher.isSymlink(hostsFile)) {
            "Debian /etc/hosts must not be a symlink"
        }
        val current = if (hostsFile.isFile) hostsFile.readText() else "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n"
        val lines = current.lines()
        val start = lines.indexOf(HOSTS_START)
        val end = lines.indexOf(HOSTS_END)
        val preserved = when {
            start >= 0 && end >= start -> (lines.take(start) + lines.drop(end + 1)).joinToString("\n").trimEnd()
            start >= 0 -> lines.take(start).joinToString("\n").trimEnd()
            end >= 0 -> lines.filterIndexed { index, _ -> index != end }.joinToString("\n").trimEnd()
            else -> current.trimEnd()
        }
        val managed = buildString {
            append(HOSTS_START).append('\n')
            mappings.forEach { (address, host) -> append(address).append(' ').append(host).append('\n') }
            append(HOSTS_END)
        }
        val updated = if (preserved.isEmpty()) "$managed\n" else "$preserved\n$managed\n"
        if (updated != current) FileInstaller.install(hostsFile, updated, 420)
        return mappings
    }

    private fun resolveRepositoryHost(host: String): List<InetAddress> {
        val androidAddresses = runCatching {
            InetAddress.getAllByName(host).toList()
        }.getOrDefault(emptyList())
        if (androidAddresses.isNotEmpty()) return androidAddresses

        return DNS_OVER_HTTPS_ENDPOINTS.flatMap { endpoint ->
            listOf("A", "AAAA").flatMap { type ->
                runCatching { queryDnsOverHttps(endpoint, host, type) }.getOrDefault(emptyList())
            }
        }.distinctBy { it.hostAddress }
    }

    private fun queryDnsOverHttps(endpoint: String, host: String, type: String): List<InetAddress> {
        val encodedHost = URLEncoder.encode(host, Charsets.UTF_8.name())
        val separator = if ('?' in endpoint) '&' else '?'
        val connection = (URL("$endpoint${separator}name=$encodedHost&type=$type").openConnection() as HttpsURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/dns-json")
        }
        return try {
            if (connection.responseCode != HttpsURLConnection.HTTP_OK) return emptyList()
            parseDnsJsonRecords(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseDnsJsonAddresses(payload: String): List<Inet4Address> =
        parseDnsJsonRecords(payload).filterIsInstance<Inet4Address>()

    internal fun parseDnsJsonRecords(payload: String): List<InetAddress> {
        val status = Regex("\"Status\"\\s*:\\s*(\\d+)")
            .find(payload)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (status != 0) return emptyList()
        val answers = Regex("\"Answer\"\\s*:\\s*\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL)
            .find(payload)?.groupValues?.getOrNull(1) ?: return emptyList()
        val recordPattern = Regex("\\{([^{}]*)\\}")
        val typePattern = Regex("\"type\"\\s*:\\s*(\\d+)")
        val dataPattern = Regex("\"data\"\\s*:\\s*\"([^\"]*)\"")
        return recordPattern.findAll(answers).mapNotNull { record ->
            val fields = record.groupValues[1]
            val type = typePattern.find(fields)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val data = dataPattern.find(fields)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
            when (type) {
                1 -> parseIpv4Address(data)
                28 -> parseIpv6Address(data)
                else -> null
            }
        }.distinctBy { it.hostAddress }.toList()
    }

    private fun parseIpv6Address(value: String): Inet6Address? {
        if (value.isEmpty() || value.any { it !in "0123456789abcdefABCDEF:." }) return null
        var normalized = value
        if ('.' in value) {
            val lastColon = value.lastIndexOf(':')
            if (lastColon < 0) return null
            val ipv4 = parseIpv4Address(value.substring(lastColon + 1)) ?: return null
            val octets = ipv4.address
            val high = ((octets[0].toInt() and 0xff) shl 8) or (octets[1].toInt() and 0xff)
            val low = ((octets[2].toInt() and 0xff) shl 8) or (octets[3].toInt() and 0xff)
            normalized = value.substring(0, lastColon + 1) +
                "%04x:%04x".format(java.util.Locale.ROOT, high, low)
        }
        val compression = normalized.indexOf("::")
        if (compression >= 0 && normalized.indexOf("::", compression + 2) >= 0) return null
        val head = if (compression >= 0) normalized.substring(0, compression) else normalized
        val tail = if (compression >= 0) normalized.substring(compression + 2) else ""
        val headGroups = if (head.isEmpty()) emptyList() else head.split(':')
        val tailGroups = if (tail.isEmpty()) emptyList() else tail.split(':')
        val groups = headGroups + tailGroups
        val validGroup = { group: String ->
            group.isNotEmpty() && group.length <= 4 && group.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        }
        if (groups.any { !validGroup(it) }) return null
        val zeros = when {
            compression < 0 -> {
                if (groups.size != 8) return null
                0
            }
            else -> {
                if (groups.size >= 8) return null
                8 - groups.size
            }
        }
        val expanded = headGroups + List(zeros) { "0" } + tailGroups
        val bytes = ByteArray(16)
        for (index in expanded.indices) {
            val group = expanded[index].toInt(16)
            bytes[index * 2] = (group shr 8).toByte()
            bytes[index * 2 + 1] = group.toByte()
        }
        return runCatching { Inet6Address.getByAddress(null, bytes, -1) }.getOrNull()
    }

    private fun parseIpv4Address(value: String): Inet4Address? {
        val octets = value.split('.')
        if (octets.size != 4) return null
        val bytes = ByteArray(4)
        for (index in octets.indices) {
            val octet = octets[index].toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            bytes[index] = octet.toByte()
        }
        return InetAddress.getByAddress(bytes) as? Inet4Address
    }

    internal fun enforceRepositoryHttps(root: File) {
        val apt = File(root, "etc/apt")
        val sourceFiles = buildList {
            add(File(apt, "sources.list").takeIf { it.isFile })
            File(apt, "sources.list.d").listFiles()
                ?.filter { it.isFile && (it.extension == "list" || it.extension == "sources") }
                ?.forEach(::add)
        }.filterNotNull()
        val insecureDebianUrl = Regex("(?i)http://((?:[a-z0-9-]+\\.)*debian\\.org)(?=[:/\\s])")
        sourceFiles.filterNot(ElfLinkerPatcher::isSymlink).forEach { source ->
            val current = runCatching { source.readText() }.getOrNull() ?: return@forEach
            val updated = insecureDebianUrl.replace(current, "https://$1")
            if (updated != current) FileInstaller.install(source, updated, 420)
        }
    }

    internal fun parseCustomDnsServers(value: String): List<String> {
        val entries = value.split(Regex("[,;\\s]+")).filter(String::isNotBlank)
        require(entries.size <= 3) { "at most three DNS addresses are supported" }
        return entries.map { entry ->
            require('%' !in entry) { "scoped IPv6 DNS addresses are not supported" }
            val address = if (':' in entry) {
                parseIpv6Address(entry)
            } else {
                val parts = entry.split('.')
                val octets = parts.map { it.toIntOrNull() }
                val validIpv4 = octets.size == 4 && octets.all { it != null && it in 0..255 }
                if (validIpv4) InetAddress.getByAddress(octets.map { it!!.toByte() }.toByteArray()) else null
            }
            require(address != null && !address.isAnyLocalAddress && !address.isLoopbackAddress) {
                "DNS entries must be valid non-local IP addresses"
            }
            address.hostAddress.substringBefore('%')
        }.distinct()
    }

    internal fun isValidHostname(value: String): Boolean {
        val normalized = value.trim().lowercase(java.util.Locale.ROOT)
        return normalized.isEmpty() ||
            (normalized.length <= 253 && Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*$").matches(normalized))
    }

    private fun customDnsServers(context: Context): List<String> = runCatching {
        parseCustomDnsServers(PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_CUSTOM_DNS_SERVERS, "").orEmpty())
    }.onFailure { Log.w(TAG, "Ignoring invalid custom DNS settings", it) }.getOrDefault(emptyList())

    private fun configuredHostname(context: Context): String {
        val value = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_CUSTOM_HOSTNAME, "").orEmpty().trim()
        return value.ifBlank { "localhost" }.lowercase(java.util.Locale.ROOT)
    }

    private fun updateHostnameFile(root: File, hostname: String) {
        val etc = File(root, "etc")
        if (ElfLinkerPatcher.isSymlink(etc) || !etc.isDirectory) return
        val hostnameFile = File(etc, "hostname")
        if (ElfLinkerPatcher.isSymlink(hostnameFile)) return
        if (!hostnameFile.isFile || runCatching { hostnameFile.readText().trim() }.getOrNull() != hostname) {
            runCatching { FileInstaller.install(hostnameFile, "$hostname\n", 420) }
        }
    }

    private fun updateAndroidHostFile(root: File, context: Context) {
        val etc = File(root, "etc")
        if (ElfLinkerPatcher.isSymlink(etc) || !etc.isDirectory) return
        val hostFile = File(etc, "miauterm-android-host")
        if (ElfLinkerPatcher.isSymlink(hostFile)) return
        val value = "${Build.MANUFACTURER} ${Build.MODEL}".trim() + "\n"
        if (runCatching { hostFile.readText() }.getOrNull() != value) {
            runCatching { FileInstaller.install(hostFile, value, 420) }
        }
    }

    internal fun resolverAddresses(dnsServers: List<InetAddress>, customDnsServers: List<String> = emptyList()): List<String> {
        if (customDnsServers.isNotEmpty()) return customDnsServers.distinct().take(3)
        val androidServers = dnsServers
            .filterNot { it.isLoopbackAddress || it.isAnyLocalAddress }
            .map { it.hostAddress.substringBefore('%') }
        return (androidServers.take(1) + FALLBACK_DNS_SERVERS).distinct().take(3)
    }

    internal fun updateResolvConf(
        root: File,
        dnsServers: List<InetAddress>,
        customDnsServers: List<String> = emptyList()
    ): Boolean {
        val etc = File(root, "etc")
        check(!ElfLinkerPatcher.isSymlink(etc) && (etc.isDirectory || etc.mkdirs())) {
            "Could not access Debian /etc for network configuration"
        }
        val resolvConf = File(etc, "resolv.conf")
        if (ElfLinkerPatcher.isSymlink(resolvConf)) {
            Log.i(TAG, "Leaving the user-managed /etc/resolv.conf symlink unchanged")
            return false
        }

        val current = if (resolvConf.isFile) resolvConf.readText() else ""
        val servers = resolverAddresses(dnsServers, customDnsServers)
        val updated = buildString {
            append(if (customDnsServers.isEmpty()) RESOLV_CONF_HEADER else CUSTOM_RESOLV_CONF_HEADER)
            servers.forEach { append("nameserver ").append(it).append('\n') }
            append("options timeout:2 attempts:2\n")
        }
        if (updated == current) return false
        return FileInstaller.install(resolvConf, updated, 420)
    }

    internal fun verifyNetworkCommands(root: File) {
        val invalidCommand = invalidNetworkCommand(root)
        check(invalidCommand == null) {
            "Debian network command /$invalidCommand is missing, truncated, or not an executable ARM64 binary."
        }
    }

    private fun invalidNetworkCommand(root: File): String? = invalidCommand(root, networkCommands)

    private fun networkDnsServers(context: Context): List<InetAddress> {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity?.activeNetwork ?: return emptyList()
        return connectivity.getLinkProperties(network)?.dnsServers.orEmpty()
    }

    private fun ensureNetworkTools(context: Context, root: File) {
        updateResolvConf(root, networkDnsServers(context), customDnsServers(context))
        provisionOptionalNetworkTools(context, root)
    }

    private fun provisionOptionalNetworkTools(context: Context, root: File) {
        try {
            if (invalidNetworkCommand(root) == null) {
                ensureSshClient(context, root)
                return
            }
            val aptOptions = listOf(
                "-o", "Acquire::http::Timeout=30",
                "-o", "Acquire::https::Timeout=30",
                "-o", "Acquire::Retries=1"
            )
            try {
                runRootfsCommand(context, root, listOf("/usr/bin/apt-get") + aptOptions + "update")
                runRootfsCommand(
                    context,
                    root,
                    listOf("/usr/bin/apt-get") + aptOptions +
                        listOf("install", "-y", "--no-install-recommends") + networkPackages
                )
            } catch (httpError: Exception) {
                Log.w(TAG, "Debian HTTP repository access failed; retrying with HTTPS", httpError)
                enforceRepositoryHttps(root)
                runRootfsCommand(context, root, listOf("/usr/bin/apt-get") + aptOptions + "update")
                runRootfsCommand(
                    context,
                    root,
                    listOf("/usr/bin/apt-get") + aptOptions +
                        listOf("install", "-y", "--no-install-recommends") + networkPackages
                )
            }
            verifyNetworkCommands(root)
            ensureSshClient(context, root)
        } catch (error: Exception) {
            Log.w(TAG, "Could not provision optional Debian network tools; install them later with apt", error)
            recordRuntimeEvent(context, "Optional Debian network tools skipped: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun ensureSshClient(context: Context, root: File) {
        val ssh = File(root, "usr/bin/ssh")
        if (ssh.isFile && ssh.canExecute() && isArm64Elf(ssh)) return
        try {
            val aptOptions = listOf(
                "-o", "Acquire::http::Timeout=8",
                "-o", "Acquire::https::Timeout=8",
                "-o", "Acquire::Retries=0"
            )
            runRootfsCommand(context, root, listOf("/usr/bin/apt-get") + aptOptions + "update")
            runRootfsCommand(
                context,
                root,
                listOf("/usr/bin/apt-get") + aptOptions + listOf("install", "-y", "--no-install-recommends", "openssh-client")
            )
            check(ssh.isFile && ssh.canExecute() && isArm64Elf(ssh)) { "Debian did not install an ARM64 OpenSSH client" }
        } catch (error: Exception) {
            Log.w(TAG, "Could not provision Debian openssh-client; install it later with apt", error)
            recordRuntimeEvent(context, "Optional Debian SSH client install skipped: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun runRootfsCommand(context: Context, root: File, command: List<String>) {
        val args = mutableListOf(
            "--link2symlink",
            "--kill-on-exit",
            "--rootfs=${root.absolutePath}",
            "--root-id",
            "--cwd=/root"
        )
        appendPseudoFilesystemBinds(args, root)
        args += command

        val processBuilder = ProcessBuilder(listOf(shellCommand(context)) + args)
            .directory(File(root, "root"))
            .redirectErrorStream(true)
        val runtimeEnvironment = environment(context, failsafe = true, rootfs = root)
        processBuilder.environment().apply {
            clear()
            runtimeEnvironment.forEach { entry ->
                val separator = entry.indexOf("=")
                if (separator > 0) put(entry.substring(0, separator), entry.substring(separator + 1))
            }
            put("HOME", "/root")
            put("USER", "root")
            put("LOGNAME", "root")
            put("SHELL", "/bin/sh")
            put("LANG", "C")
            put("LC_ALL", "C")
            put("DEBIAN_FRONTEND", "noninteractive")
        }
        val process = processBuilder.start()
        val (output, exitCode) = runProcess(process, 600_000)
        check(exitCode == 0) {
            output.ifEmpty { "${command.joinToString(" ")} failed (exit $exitCode)" }
        }
    }

    data class LinuxAccount(val name: String, val uid: Int, val gid: Int, val home: String, val shell: String)

    fun accounts(root: File): List<LinuxAccount> {
        val rootCanonical = runCatching { root.canonicalFile }.getOrNull() ?: return emptyList()
        fun guestPath(path: String): File? {
            if (!path.startsWith("/") || path.split('/').any { it == ".." }) return null
            val candidate = runCatching { File(rootCanonical, path.removePrefix("/")).canonicalFile }.getOrNull() ?: return null
            return candidate.takeIf { ElfLinkerPatcher.isContainedInRoot(it, rootCanonical.path) }
        }

        val passwd = File(rootCanonical, "etc/passwd")
        val safePasswd = guestPath("/etc/passwd")?.takeIf { it.isFile && !ElfLinkerPatcher.isSymlink(passwd) }
        val parsed = safePasswd?.let { file -> runCatching { file.readLines().mapNotNull { line ->
            val fields = line.split(':')
            val uid = fields.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null
            val gid = fields.getOrNull(3)?.toIntOrNull() ?: return@mapNotNull null
            val name = fields.getOrNull(0)?.takeIf { it.matches(Regex("[a-z_][a-z0-9_-]*[$]?")) } ?: return@mapNotNull null
            val home = fields.getOrNull(5)?.takeIf { it.startsWith("/") } ?: return@mapNotNull null
            val shell = fields.getOrNull(6)?.takeIf { it.startsWith("/") } ?: return@mapNotNull null
            val homeDir = guestPath(home)?.takeIf { it.isDirectory } ?: return@mapNotNull null
            val shellFile = guestPath(shell)?.takeIf { it.isFile && it.canExecute() } ?: return@mapNotNull null
            LinuxAccount(name, uid, gid, "/" + homeDir.relativeTo(rootCanonical).invariantSeparatorsPath,
                "/" + shellFile.relativeTo(rootCanonical).invariantSeparatorsPath)
        } }.getOrDefault(emptyList()) }.orEmpty()
        val rootHome = guestPath("/root")?.takeIf { it.isDirectory }?.let {
            "/" + it.relativeTo(rootCanonical).invariantSeparatorsPath
        } ?: "/root"
        return listOf(LinuxAccount("root", 0, 0, rootHome, "/bin/bash")) + parsed.filter {
            it.uid in 1 until 60000 && it.name != "root" &&
                it.shell !in setOf("/bin/false", "/usr/sbin/nologin", "/sbin/nologin")
        }.distinctBy { it.name }
    }

    fun selectedAccount(context: Context, root: File = Environment.getCortexRoot(context)): LinuxAccount {
        val wanted = PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_LINUX_USER, "")
            ?.trim().orEmpty().ifBlank { "root" }
        return accountByName(root, wanted)
            ?: accounts(root).firstOrNull()
            ?: LinuxAccount("root", 0, 0, "/root", "/bin/bash")
    }

    fun accountByName(root: File, name: String): LinuxAccount? = accounts(root).firstOrNull { it.name == name }

    const val PREF_LINUX_USER = "linux_user"
    const val PREF_SHARED_FOLDER_ACCESS = "shared_folder_access_enabled"

    fun hasSharedFolderPermission(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return true
        val granted = android.content.pm.PackageManager.PERMISSION_GRANTED
        return context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == granted &&
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == granted
    }

    fun isSharedFolderAccessEnabled(context: Context): Boolean {
        if (!hasSharedFolderPermission(context)) return false
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(PREF_SHARED_FOLDER_ACCESS, true)
    }

    private fun publicDirectory(context: Context, name: String): File? {
        if (!isSharedFolderAccessEnabled(context)) return null
        val directory = File(AndroidEnvironment.getExternalStorageDirectory(), name)
        if (!directory.exists() && !directory.mkdirs()) return null
        return directory.takeIf { it.isDirectory && it.canRead() }
    }

    fun environment(context: Context, failsafe: Boolean = false, rootfs: File = Environment.getCortexRoot(context), accountOverride: LinuxAccount? = null): Array<String> {
        val proot = File(context.filesDir, "proot")
        val prootTemp = File(context.cacheDir, "proot-tmp").apply { mkdirs() }
        val tz = java.util.TimeZone.getDefault().id
        val account = accountOverride ?: selectedAccount(context, rootfs)
        return arrayOf(
            "HOME=${account.home}",
            "USER=${account.name}",
            "LOGNAME=${account.name}",
            "HOSTNAME=${configuredHostname(context)}",
            "MIAUTERM_HOSTNAME=${configuredHostname(context)}",
            "MIAUTERM_ANDROID_DEVICE=${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            "SHELL=${account.shell.ifBlank { shellPath(rootfs)?.let { "/" + it.relativeTo(rootfs).invariantSeparatorsPath } ?: "/bin/sh" }}",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            "DEBIAN_FRONTEND=noninteractive",
            "TMPDIR=/tmp",
            "TZ=$tz",
            "PROOT_LOADER=${File(proot, "loader").absolutePath}",
            "PROOT_TMP_DIR=${prootTemp.absolutePath}",
            "LD_LIBRARY_PATH=${proot.absolutePath}",
            "MIAUTERM_PROOT_DIAGNOSTICS=${if (diagnosticsEnabled(context)) 1 else 0}"
        ) + if (failsafe) arrayOf("PROOT_NO_SECCOMP=1") else emptyArray()
    }
}
