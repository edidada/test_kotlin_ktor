package ecshop.db

/** Splits a multi-statement SQL script on semicolons that are outside single-quoted literals. */
object SqlScript {
    fun split(script: String): List<String> {
        val statements = ArrayList<String>()
        val current = StringBuilder()
        var inString = false
        var i = 0
        while (i < script.length) {
            val ch = script[i]
            when {
                ch == '\'' && !inString -> { inString = true; current.append(ch) }
                ch == '\'' && inString -> {
                    // '' is an escaped quote inside a literal.
                    if (i + 1 < script.length && script[i + 1] == '\'') {
                        current.append("''"); i++
                    } else {
                        inString = false; current.append(ch)
                    }
                }
                ch == ';' && !inString -> {
                    statements.add(current.toString()); current.setLength(0)
                }
                ch == '-' && !inString && i + 1 < script.length && script[i + 1] == '-' -> {
                    // line comment: skip to end of line
                    while (i < script.length && script[i] != '\n') i++
                    current.append('\n')
                }
                else -> current.append(ch)
            }
            i++
        }
        if (current.toString().isNotBlank()) statements.add(current.toString())
        return statements.map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun loadResource(resourcePath: String): String =
        SqlScript::class.java.getResourceAsStream(resourcePath)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("SQL resource not found: $resourcePath")
}
