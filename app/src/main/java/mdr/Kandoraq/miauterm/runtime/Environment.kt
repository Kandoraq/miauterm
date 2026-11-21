package salu.Kandoraq.Miauterm.runtime

import android.content.Context
import java.io.File

object Environment {
    fun getMiautermRoot(context: Context): File = File(context.filesDir, "debian")

    fun isInside(root: File, file: File): Boolean = try {
        val canonicalRoot = root.canonicalFile.path
        val canonicalFile = file.canonicalFile.path
        canonicalFile == canonicalRoot || canonicalFile.startsWith(canonicalRoot + File.separator)
    } catch (_: Exception) {
        false
    }

    fun getHomeDir(context: Context): File {
        val root = getMiautermRoot(context)
        if (!root.isDirectory) return context.filesDir
        val home = runCatching { DebianRuntime.selectedAccount(context, root).home }.getOrNull() ?: "/root"
        return File(root, home.removePrefix("/"))
    }

}
