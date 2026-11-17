package org.cortex.terminal.runtime

import android.content.Context
import java.io.File

internal object MiautermCommandInstaller {
    private const val MODE_EXECUTABLE = 493
    private const val COMMAND_PATH = "usr/local/bin/miauterm-cmd"
    private const val NOTIFY_SEND_PATH = "usr/local/bin/notify-send"
    private const val SU_PATH = "usr/local/bin/su"

    fun install(context: Context, root: File) {
        val command = File(root, COMMAND_PATH)
        val script = context.assets.open("miauterm-cmd").use { it.readBytes() }
        FileInstaller.install(command, script, MODE_EXECUTABLE)
        check(command.canExecute()) { "Could not make miauterm-cmd executable" }
        FileInstaller.symlink("miauterm-cmd", File(root, NOTIFY_SEND_PATH))
        val su = File(root, SU_PATH)
        FileInstaller.install(su, context.assets.open("miauterm-su").use { it.readBytes() }, MODE_EXECUTABLE)
        check(su.canExecute()) { "Could not make Miauterm su executable" }
    }

    fun isInstalled(root: File): Boolean {
        val command = File(root, COMMAND_PATH)
        val link = File(root, NOTIFY_SEND_PATH)
        val target = ElfLinkerPatcher.readSymlink(link)
        val linkInstalled = target != null && runCatching {
            val resolved = if (target.startsWith('/')) File(root, target.trimStart('/')) else File(link.parentFile, target)
            resolved.canonicalFile == command.canonicalFile
        }.getOrDefault(false)
        return command.let { it.isFile && it.canExecute() } &&
            linkInstalled &&
            File(root, SU_PATH).let { it.isFile && it.canExecute() }
    }
}
