package org.cortex.terminal.session

internal fun sessionPromptLabel(accountName: String, directory: String? = null): String {
    val label = when {
        directory.isNullOrBlank() || directory == "/" -> "~"
        directory.startsWith("/root") -> "~" + directory.removePrefix("/root")
        else -> directory.substringAfterLast('/').ifEmpty { "/" }
    }
    return "$accountName@localhost $label${if (accountName == "root") '#' else '$'}"
}
