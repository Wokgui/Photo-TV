package fr.wokgui.phototv

object TextWrapping {
    fun lines(text: String, width: Float, measure: (String) -> Float): List<String> {
        val result = mutableListOf<String>()
        text.split('\n').forEach { paragraph ->
            var rest = paragraph
            if (rest.isEmpty()) result.add("")
            while (rest.isNotEmpty()) {
                if (measure(rest) <= width) { result.add(rest); break }
                var end = 0
                var next = 0
                while (next < rest.length) {
                    next += Character.charCount(rest.codePointAt(next))
                    if (measure(rest.substring(0,next)) > width && end > 0) break
                    end = next
                }
                val space = rest.lastIndexOf(' ',end-1)
                if (space > 0) end = space
                result.add(rest.substring(0,end))
                rest = rest.substring(end).trimStart()
            }
        }
        return result.ifEmpty { listOf("") }
    }
}
