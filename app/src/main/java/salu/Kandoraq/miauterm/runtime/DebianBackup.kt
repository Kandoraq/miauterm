package salu.Kandoraq.miauterm.runtime

import android.content.Context
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object DebianBackup {
    private const val MANIFEST = "META-INF/miauterm-backup.json"
    private const val ROOT_PREFIX = "rootfs/"
    private const val FORMAT_VERSION = 1
    private const val MAX_IMPORT_BYTES = 2L * 1024 * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 32 * 1024 * 1024

    fun export(context: Context, uri: Uri) {
        val root = Environment.getMiautermRoot(context)
        require(isDebian(root)) { "Debian is not installed" }
        val lock = FileLocks.tryLock(listOf(File(context.filesDir, ".debian-install.lock")))
            ?: error("Another Debian operation is already in progress")
        val rootSize = runCatching { root.walkTopDown().sumOf { if (it.isFile) it.length() else 0L } }.getOrDefault(0L)
        val stat = android.os.StatFs(context.cacheDir.path)
        require(stat.availableBytes >= rootSize + 64L * 1024 * 1024) { "Not enough free space for the backup" }
        val temp = File(context.cacheDir, "miauterm-backup-${System.currentTimeMillis()}.zip")
        lock.use {
        try {
            val entries = JSONArray()
            ZipOutputStream(BufferedOutputStream(temp.outputStream())).use { zip ->
                zip.setLevel(6)
                fun visit(file: File, relative: String) {
                    val stat = try {
                        Os.lstat(file.absolutePath)
                    } catch (e: Exception) {
                        throw IllegalStateException("Cannot read $relative for backup", e)
                    }
                    val mode = stat.st_mode
                    val type = mode and OsConstants.S_IFMT
                    val item = JSONObject().put("path", relative).put("mode", mode)
                    when (type) {
                        OsConstants.S_IFDIR -> {
                            item.put("type", "directory")
                            entries.put(item)
                            val zipName = ROOT_PREFIX + relative.trimEnd('/') + "/"
                            zip.putNextEntry(ZipEntry(zipName))
                            zip.closeEntry()
                            file.listFiles().orEmpty().sortedBy { it.name }.forEach { child ->
                                visit(child, if (relative.isEmpty()) child.name else "$relative/${child.name}")
                            }
                        }
                        OsConstants.S_IFREG -> {
                            item.put("type", "file")
                            entries.put(item)
                            zip.putNextEntry(ZipEntry(ROOT_PREFIX + relative))
                            file.inputStream().buffered().use { input -> input.copyTo(zip) }
                            zip.closeEntry()
                        }
                        OsConstants.S_IFLNK -> {
                            item.put("type", "symlink").put("target", Os.readlink(file.absolutePath))
                            entries.put(item)
                        }
                        else -> throw IllegalStateException("Backup cannot include special file: $relative")
                    }
                }
                root.listFiles().orEmpty().sortedBy { it.name }.forEach { visit(it, it.name) }
                val manifest = JSONObject()
                    .put("format", FORMAT_VERSION)
                    .put("distribution", "debian")
                    .put("entries", entries)
                    .toString()
                    .toByteArray(Charsets.UTF_8)
                require(manifest.size <= MAX_MANIFEST_BYTES) { "Debian backup manifest is too large" }
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(manifest)
                zip.closeEntry()
            }
            verifyExport(temp)
            val output = context.contentResolver.openOutputStream(uri, "w")
                ?: error("Cannot open backup destination")
            output.use { sink -> temp.inputStream().use { it.copyTo(sink) } }
        } finally {
            temp.delete()
        }
        }
    }

    private fun verifyExport(archive: File) {
        ZipFile(archive).use { zip ->
            val manifestEntry = zip.getEntry(MANIFEST) ?: error("Backup manifest is missing")
            val manifest = JSONObject(zip.getInputStream(manifestEntry).use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            })
            require(manifest.optInt("format") == FORMAT_VERSION && manifest.optString("distribution") == "debian") {
                "Backup manifest is invalid"
            }
            val entries = manifest.getJSONArray("entries")
            val paths = HashSet<String>(entries.length())
            for (index in 0 until entries.length()) {
                val item = entries.getJSONObject(index)
                val path = safePath(item.getString("path"))
                require(paths.add(path)) { "Duplicate path in backup" }
                when (item.getString("type")) {
                    "directory" -> require(zip.getEntry(ROOT_PREFIX + path.trimEnd('/') + "/") != null) {
                        "Backup entry is missing: $path"
                    }
                    "file" -> require(zip.getEntry(ROOT_PREFIX + path) != null) {
                        "Backup entry is missing: $path"
                    }
                    "symlink" -> Unit
                    else -> error("Unknown backup entry type")
                }
            }
        }
    }

    fun import(context: Context, uri: Uri) {
        val lock = FileLocks.tryLock(listOf(File(context.filesDir, ".debian-install.lock")))
            ?: error("Another Debian operation is already in progress")
        lock.use {
            val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open backup file")
            val archive = File(context.cacheDir, "miauterm-restore-${System.currentTimeMillis()}.zip")
            try {
                input.use { source ->
                    archive.outputStream().buffered().use { sink ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_IMPORT_BYTES) { "Backup is too large" }
                            sink.write(buffer, 0, count)
                        }
                    }
                }
                restoreArchive(context, archive)
            } finally {
                archive.delete()
            }
        }
    }

    private fun restoreArchive(context: Context, archive: File) {
        val root = Environment.getMiautermRoot(context)
        val staging = File(context.filesDir, "debian-restore-staging")
        val previous = File(context.filesDir, "debian-restore-previous")
        val failed = File(context.filesDir, "debian-restore-failed")
        if (!root.exists() && previous.isDirectory) {
            require(previous.renameTo(root)) { "Cannot recover the previous Debian system" }
        }
        require(SafeDelete.deleteRecursively(staging)) { "Cannot clear restore staging directory" }
        require(staging.mkdirs() || staging.isDirectory) { "Cannot create restore staging directory" }
        try {
            ZipFile(archive).use { zip ->
                val manifestEntry = zip.getEntry(MANIFEST) ?: error("This is not a Miauterm backup")
                require(manifestEntry.size in 0..MAX_MANIFEST_BYTES.toLong()) { "Backup manifest is too large" }
                val manifestBytes = zip.getInputStream(manifestEntry).use { source ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_MANIFEST_BYTES) { "Backup manifest is too large" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
                require(manifest.optInt("format") == FORMAT_VERSION && manifest.optString("distribution") == "debian") {
                    "Unsupported backup format"
                }
                val entries = manifest.getJSONArray("entries")
                val metadata = HashMap<String, JSONObject>(entries.length())
                for (index in 0 until entries.length()) {
                    val item = entries.getJSONObject(index)
                    val path = safePath(item.getString("path"))
                    require(metadata.put(path, item) == null) { "Duplicate path in backup" }
                }
                for (path in metadata.keys) {
                    var parent = File(path).parent
                    while (parent != null) {
                        require(metadata[parent]?.optString("type") != "symlink") { "Backup paths cannot descend through symbolic links" }
                        parent = File(parent).parent
                    }
                }
                var written = 0L
                val zipEntries = zip.entries()
                while (zipEntries.hasMoreElements()) {
                    val item = zipEntries.nextElement()
                    if (!item.name.startsWith(ROOT_PREFIX) || item.name == ROOT_PREFIX) continue
                    val path = safePath(item.name.removePrefix(ROOT_PREFIX).trimEnd('/'))
                    val meta = metadata[path] ?: error("Backup metadata is incomplete")
                    val target = File(staging, path)
                    when (meta.getString("type")) {
                        "directory" -> target.mkdirs()
                        "file" -> {
                            target.parentFile?.mkdirs()
                            zip.getInputStream(item).use { source ->
                                target.outputStream().buffered().use { sink ->
                                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                    while (true) {
                                        val count = source.read(buffer)
                                        if (count < 0) break
                                        written += count
                                        require(written <= MAX_IMPORT_BYTES) { "Expanded backup is too large" }
                                        sink.write(buffer, 0, count)
                                    }
                                }
                            }
                            Os.chmod(target.absolutePath, meta.getInt("mode") and 0x1ff)
                        }
                        "symlink" -> Unit
                        else -> error("Unknown file type in backup")
                    }
                }
                for ((path, meta) in metadata) {
                    if (meta.getString("type") == "symlink") {
                        val target = File(staging, path)
                        target.parentFile?.mkdirs()
                        val link = safeSymlinkTarget(path, meta.getString("target"))
                        Os.symlink(link, target.absolutePath)
                    }
                }
                metadata.entries
                    .filter { it.value.getString("type") == "directory" }
                    .sortedByDescending { it.key.count { c -> c == '/' } }
                    .forEach { (path, meta) -> Os.chmod(File(staging, path).absolutePath, meta.getInt("mode") and 0x1ff) }
                for ((path, meta) in metadata) {
                    val target = File(staging, path)
                    when (meta.getString("type")) {
                        "directory" -> require(target.isDirectory) { "Restored directory is missing: $path" }
                        "file" -> require(target.isFile && !ElfLinkerPatcher.isSymlink(target)) {
                            "Restored file is missing: $path"
                        }
                        "symlink" -> require(ElfLinkerPatcher.isSymlink(target)) { "Restored symlink is missing: $path" }
                        else -> error("Unknown file type in backup")
                    }
                }
                DebianRuntime.prepareRestoredRootfs(context, staging)
            }
            require(SafeDelete.deleteRecursively(previous)) { "Cannot clear previous restore" }
            if (root.exists()) require(root.renameTo(previous)) { "Cannot move current Debian system" }
            if (!staging.renameTo(root)) {
                if (previous.exists() && !previous.renameTo(root)) {
                    error("Cannot install restored Debian system or roll back the current system")
                }
                error("Cannot install restored Debian system")
            }
            if (runCatching { DebianRuntime.verifyRootfs(root) }.isFailure) {
                SafeDelete.deleteRecursively(failed)
                if (root.renameTo(failed) && previous.isDirectory && previous.renameTo(root)) {
                    error("Restored Debian system failed verification; the previous system was restored")
                }
                error("Restored Debian system failed verification")
            }
            SafeDelete.deleteRecursively(previous)
            SafeDelete.deleteRecursively(failed)
        } finally {
            SafeDelete.deleteRecursively(staging)
        }
    }

    private fun isDebian(root: File): Boolean =
        File(root, "etc/os-release").takeIf { it.isFile }?.readText()?.lineSequence()
            ?.any { it.trim() == "ID=debian" } == true &&
            File(root, "usr/bin/bash").let { it.isFile && it.canExecute() } &&
            File(root, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1").isFile

    internal fun safeSymlinkTarget(linkPath: String, target: String): String {
        safePath(linkPath)
        require(target.isNotEmpty() && '\u0000' !in target) { "Invalid symlink in backup" }

        val linkParent = linkPath.split('/').dropLast(1)
        val resolvedTarget = if (target.startsWith('/')) mutableListOf() else linkParent.toMutableList()
        for (part in target.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> {
                    if (resolvedTarget.isNotEmpty()) resolvedTarget.removeAt(resolvedTarget.lastIndex)
                }
                else -> resolvedTarget += part
            }
        }

        var common = 0
        while (common < linkParent.size && common < resolvedTarget.size &&
            linkParent[common] == resolvedTarget[common]
        ) common++
        return (List(linkParent.size - common) { ".." } + resolvedTarget.drop(common))
            .joinToString("/").ifEmpty { "." }
    }

    private fun safePath(path: String): String {
        require(path.isNotEmpty() && !path.startsWith('/') && '\u0000' !in path) { "Invalid backup path" }
        require(path.split('/').none { it == ".." || it == "." || it.isEmpty() }) { "Invalid backup path" }
        return path
    }
}
