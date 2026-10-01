package arenliel.musicwidget

/**
 * Conjunto Letras-Robustez-1: resultado de interpretar un bloque LRC. `entries` es una línea de
 * tiempo coherente (marcas de tiempo no decrecientes); `discardedCount` es cuántas líneas con
 * texto se descartaron por no caber en esa línea de tiempo.
 */
data class ParsedLrc(val entries: List<LyricsEntry>, val discardedCount: Int)

/**
 * Conjunto Letras-Robustez-1: lógica pura (sin Android, sin red, sin estado) que decide dos cosas
 * sobre una letra sincronizada: (1) qué líneas del bloque LRC forman una línea de tiempo válida,
 * y (2) qué línea corresponde a una posición de reproducción dada.
 *
 * LRCLIB es una base colaborativa: un registro puede traer marcas de tiempo que retroceden (por
 * ejemplo, líneas sobrantes al final con marcas de 1 o 2 segundos). El motor de letras asume una
 * lista ordenada; este objeto garantiza esa suposición en un único lugar, al interpretar el LRC,
 * en vez de confiar en que los datos externos vengan bien.
 */
object LyricsTimeline {

    private val LINE_REGEX = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\](.*)")

    /**
     * Interpreta un bloque LRC. Cada línea con marca `[mm:ss.xx]` (o `.xxx`) y texto no vacío es
     * candidata. Se conserva la línea de tiempo más larga que avanza de verdad (marcas
     * estrictamente crecientes, respetando el orden del archivo) y, junto a cada línea
     * conservada, las líneas que le siguen inmediatamente con su misma marca (dos versos cantados
     * a la vez). El resto se descarta y se cuenta en `discardedCount`. Una letra ya coherente se
     * conserva completa.
     *
     * Por qué "estrictamente crecientes" y no "no decrecientes": un registro dañado puede traer una
     * cola larga de líneas con la misma marca repetida (por ejemplo, 22 líneas en 0:01.00 y 9 en
     * 0:02.00). Contadas como "no decrecientes" esas 31 líneas ganarían a las 23 líneas buenas y
     * se descartaría la letra real; contadas como "avanzan de verdad", la cola vale 2 y la letra
     * real vale 23.
     */
    fun parse(lrc: String): ParsedLrc {
        val candidates = mutableListOf<LyricsEntry>()
        for (line in lrc.split("\n")) {
            val match = LINE_REGEX.find(line) ?: continue
            val min = match.groupValues[1].toLong()
            val sec = match.groupValues[2].toLong()
            val msPart = match.groupValues[3]
            val ms = if (msPart.length == 2) msPart.toLong() * 10 else msPart.toLong()
            val totalMs = (min * 60 + sec) * 1000 + ms
            val text = match.groupValues[4].trim()
            if (text.isNotBlank()) candidates.add(LyricsEntry(totalMs, text))
        }
        val consistent = coherentTimeline(candidates)
        return ParsedLrc(consistent, candidates.size - consistent.size)
    }

    /**
     * Índice de la línea vigente en `positionMs`: la última línea cuya marca es menor o igual a la
     * posición. Devuelve -1 si la posición es anterior a la primera línea (o la lista está vacía).
     * Requiere una lista en orden no decreciente, como la que devuelve `parse`.
     */
    fun indexAt(entries: List<LyricsEntry>, positionMs: Long): Int {
        var low = 0
        var high = entries.size - 1
        var result = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (entries[mid].timestampMs <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    // Paso 1: columna vertebral = subsecuencia estrictamente creciente más larga (algoritmo de
    // "paciencia", O(n log n)); ante marcas iguales gana la primera del archivo.
    // Paso 2: se suman las líneas que siguen inmediatamente a una línea conservada con su misma
    // marca. Resultado determinista, en el orden original del archivo.
    private fun coherentTimeline(candidates: List<LyricsEntry>): List<LyricsEntry> {
        if (candidates.isEmpty()) return candidates
        // tails[k] = índice (en candidates) del elemento final de la mejor subsecuencia de longitud k + 1
        val tails = IntArray(candidates.size)
        val previous = IntArray(candidates.size) { -1 }
        var length = 0
        for (i in candidates.indices) {
            val timestamp = candidates[i].timestampMs
            var low = 0
            var high = length
            while (low < high) {
                val mid = (low + high) ushr 1
                if (candidates[tails[mid]].timestampMs < timestamp) low = mid + 1 else high = mid
            }
            if (low < length && candidates[tails[low]].timestampMs == timestamp) continue
            if (low > 0) previous[i] = tails[low - 1]
            tails[low] = i
            if (low == length) length++
        }
        val backbone = BooleanArray(candidates.size)
        var k = tails[length - 1]
        while (k != -1) {
            backbone[k] = true
            k = previous[k]
        }
        val result = ArrayList<LyricsEntry>(length)
        var lastKeptIndex = -1
        for (i in candidates.indices) {
            val sameStampRightAfterKept = lastKeptIndex == i - 1 && lastKeptIndex != -1 &&
                candidates[i].timestampMs == candidates[lastKeptIndex].timestampMs
            if (backbone[i] || sameStampRightAfterKept) {
                result.add(candidates[i])
                lastKeptIndex = i
            }
        }
        return result
    }
}
