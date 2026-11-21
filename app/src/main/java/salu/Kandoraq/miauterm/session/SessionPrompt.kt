package salu.Kandoraq.miauterm.session

internal fun sessionPromptLabel(accountName: String, directory: String? = null): String {
    val label = when {
        directory.isNullOrBlank() -> "~"
        directory == "/" -> "/"
        else -> directory.substringAfterLast('/').ifEmpty { "/" }
    }
    return "$accountName@localhost $label${if (accountName == "root") '#' else '$'}"
}
