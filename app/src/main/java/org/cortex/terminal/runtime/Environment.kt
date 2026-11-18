package org.cortex.terminal.runtime

import android.content.Context
import java.io.File

object Environment {
    fun getCortexRoot(context: Context): File = File(context.filesDir, "debian")

    fun isInside(root: File, file: File): Boolean = try {
        val canonicalRoot = root.canonicalFile.path
        val canonicalFile = file.canonicalFile.path
        canonicalFile == canonicalRoot || canonicalFile.startsWith(canonicalRoot + File.separator)
    } catch (_: Exception) {
        false
    }

    fun getHomeDir(context: Context): File {
        val root = getCortexRoot(context)
        val home = DebianRuntime.selectedAccount(context, root).home
        return File(root, home.removePrefix("/"))
    }

}
