package org.cortex.terminal.runtime

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

internal object SafeDelete {
    private val failure = ThreadLocal<String?>()

    val lastFailure: String?
        get() = failure.get()

    fun deleteRecursively(root: File): Boolean {
        failure.set(null)
        return try {
            val deleted = remove(root)
            if (!deleted && failure.get() == null) {
                failure.set("Could not remove ${root.absolutePath}")
            }
            deleted
        } catch (e: Throwable) {
            failure.set("${root.absolutePath}: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    private fun remove(file: File): Boolean {
        if (isLink(file)) return unlink(file)
        if (!file.exists()) return true
        if (file.isDirectory) {
            val readable = file.setReadable(true, true)
            val writable = file.setWritable(true, true)
            val executable = file.setExecutable(true, true)
            if (!readable || !writable || !executable) {
                failure.set("Could not access directory ${file.absolutePath}")
                return false
            }
            val children = file.listFiles()
            if (children == null) {
                failure.set("Could not list directory ${file.absolutePath}")
                return false
            }
            children.forEach { child ->
                if (!remove(child)) return false
            }
        }
        return unlink(file)
    }

    private fun isLink(file: File): Boolean = ElfLinkerPatcher.isSymlink(file)

    private fun unlink(entry: File): Boolean {
        if (entry.isDirectory && !isLink(entry)) {
            val deleted = entry.delete()
            if (!deleted) failure.set("Could not remove directory ${entry.absolutePath}")
            return deleted
        }
        return try {
            Os.remove(entry.absolutePath)
            true
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.ENOENT) {
                true
            } else {
                val deleted = entry.delete()
                if (!deleted) failure.set("Could not remove ${entry.absolutePath} (errno ${e.errno})")
                deleted
            }
        } catch (e: Throwable) {
            val deleted = entry.delete()
            if (!deleted) {
                failure.set("Could not remove ${entry.absolutePath}: ${e.message ?: e.javaClass.simpleName}")
            }
            deleted
        }
    }
}
