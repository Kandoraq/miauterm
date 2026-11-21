package mdr.Kandoraq.miauterm.runtime

import android.system.Os
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

internal object FileInstaller {
    private const val TAG = "FileInstaller"

    fun install(file: File, text: String, mode: Int): Boolean = install(file, text.toByteArray(Charsets.UTF_8), mode)

    @Synchronized
    fun install(file: File, bytes: ByteArray, mode: Int): Boolean {
        if (holds(file, bytes)) {
            if (!chmod(file, mode)) throw IOException("could not set permissions on ${file.path}")
            return false
        }
        val dir = file.absoluteFile.parentFile ?: throw IOException("${file.path} has no directory")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create ${dir.path}")
        val tmp = File(dir, ".${file.name}.miauterm-new")
        if (tmp.exists() || ElfLinkerPatcher.isSymlink(tmp)) {
            if (!ElfLinkerPatcher.deleteIfExists(tmp)) throw IOException("could not clear ${tmp.path}")
        }
        try {
            java.io.FileOutputStream(tmp).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            if (!chmod(tmp, mode)) throw IOException("could not set permissions on ${tmp.path}")
            if (!ElfLinkerPatcher.atomicReplace(tmp, file)) {
                throw IOException("could not atomically replace ${file.path}")
            }
            if (!holds(file, bytes)) throw IOException("installed file verification failed: ${file.path}")
            if (!chmod(file, mode)) throw IOException("could not set permissions on ${file.path}")
            return true
        } finally {
            if (tmp.exists() || ElfLinkerPatcher.isSymlink(tmp)) ElfLinkerPatcher.deleteIfExists(tmp)
        }
    }

    fun holds(file: File, bytes: ByteArray): Boolean = runCatching {
        file.isFile && file.length() == bytes.size.toLong() && !ElfLinkerPatcher.isSymlink(file) &&
            file.readBytes().contentEquals(bytes)
    }.getOrDefault(false)

    @Synchronized
    fun symlink(target: String, link: File): Boolean {
        if (ElfLinkerPatcher.readSymlink(link) == target) return false
        val dir = link.absoluteFile.parentFile ?: throw IOException("${link.path} has no directory")
        val tmp = File(dir, ".${link.name}.miauterm-link")
        ElfLinkerPatcher.deleteIfExists(tmp)
        if (!ElfLinkerPatcher.createSymlink(target, tmp)) throw IOException("could not create ${tmp.path}")
        if (!ElfLinkerPatcher.atomicReplace(tmp, link)) {
            ElfLinkerPatcher.deleteIfExists(tmp)
            throw IOException("could not replace ${link.path}")
        }
        return true
    }

    fun chmod(file: File, mode: Int): Boolean = try {
        Os.chmod(file.absolutePath, mode)
        true
    } catch (e: Exception) {
        Log.w(TAG, "chmod ${Integer.toOctalString(mode)} ${file.path} failed: ${e.message}")
        false
    }
}

internal class FileLocks private constructor(
    private val files: List<RandomAccessFile>,
    private val locks: List<FileLock>
) : AutoCloseable {
    override fun close() {
        locks.forEach { lock -> try { lock.release() } catch (e: Exception) { Log.w("FileLocks", "Could not release file lock", e) } }
        files.forEach { file -> try { file.close() } catch (e: Exception) { Log.w("FileLocks", "Could not close lock file", e) } }
    }

    companion object {

        fun tryLock(paths: List<File>): FileLocks? {
            val files = mutableListOf<RandomAccessFile>()
            val locks = mutableListOf<FileLock>()
            try {
                for (path in paths) {
                    path.parentFile?.mkdirs()
                    val raf = RandomAccessFile(path, "rw")
                    files.add(raf)
                    val lock = try { raf.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    if (lock == null) {
                        FileLocks(files, locks).close()
                        return null
                    }
                    locks.add(lock)
                }
            } catch (e: IOException) {
                FileLocks(files, locks).close()
                return null
            }
            return FileLocks(files, locks)
        }
    }
}
