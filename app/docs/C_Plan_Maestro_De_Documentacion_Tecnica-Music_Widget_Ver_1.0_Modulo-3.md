
# PLAN MAESTRO DE DOCUMENTACIÓN TÉCNICA: MUSIC WIDGET (VERSION 1.0)

## MÓDULO 3: PERSISTENCIA, IDENTIDAD Y ANALÍTICA DE ESCUCHA

Este módulo detalla la gestión de la capa de datos mediante Jetpack DataStore, el sistema de validación por llaves digitales para garantizar la integridad visual, y el motor de analítica predictiva de rachas (Streaks).

### 1. ESPECIFICACIONES TÉCNICAS DE PERSISTENCIA Y DATOS
| Parámetro | Valor / Estrategia | Justificación Técnica |
| :--- | :--- | :--- |
| **Motor de Persistencia** | Jetpack DataStore (Preferences) | Proporciona transacciones atómicas y reactividad nativa con Flow sin el overhead de SQLite/Room. |
| **Identidad Lógica** | `sessionIdentity` (pkg + title + artist) | Permite la continuidad del progreso frente a actualizaciones menores de metadatos de Spotify. |
| **Identidad Física** | `trackKey` (session + duration + album) | Garantiza que los activos (.webp, .key) coincidan exactamente con la versión de la pista (Single vs Album). |
| **Umbral de Racha (🔥)** | 3 Reproducciones (Mismo día) | "Ley de los 3": Nivel mínimo para certificar una tendencia de escucha diaria. |
| **Umbral de Racha (↺)** | 3 Días Consecutivos | Establece el estándar de "Canción en Repetición" para la analítica de calendario. |
| **Umbral de "Bendición"** | Presencia en Top 10 (isSkipped = false) | Protege canciones favoritas contra saltos accidentales que ensucien el historial. |

### 2. GESTIÓN DE IDENTIDAD DUAL (IDENTITY SYSTEM)
Se implementa una separación de capas para resolver el conflicto entre la continuidad del tiempo y la integridad de los activos visuales.

```kotlin
// Implementación en MusicNotificationListener.kt
data class MusicIdentity(
    val packageName: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long
) {
    // Capa Lógica: Identifica la "sesión de escucha" para letras y progreso
    val sessionIdentity: String
        get() = "\$packageName|\\(title|\\)artist"

    // Capa Física: Identifica el archivo exacto de arte y caché
    val trackKey: String
        get() = "\$sessionIdentity|\\(album|\\)durationMs"
}

// Validación de Sincronía Visual en MusicWidget.kt
private fun isAssetSynchronized(context: Context, expectedKey: String, assetType: String): Boolean {
    val keyFile = File(context.cacheDir, "\$assetType.key")
    if (!keyFile.exists()) return false
    return try {
        keyFile.readText().trim() == expectedKey
    } catch (e: Exception) {
        false
    }
}
````

### 3. MOTOR DE ANALÍTICA DE ESCUCHA Y RACHAS (STREAKS)

El sistema utiliza una lógica de decaimiento matemático "Zero-Timer" para calcular rachas en el momento de la lectura, optimizando el uso de batería.

```
// Implementación en MusicDataStore.kt
enum class RepeatBadge { NONE, HOT_TODAY, ON_REPEAT }

fun getBadgeFor(info: MusicInfo): RepeatBadge {
    val today = LocalDate.now().toEpochDay()

    // 1. Prioridad Absoluta: Racha de Días (Infinito ↺)
    // El estado caduca en la lectura: si pasaron > 1 día, la racha se ignora
    if (info.streakDays >= 3 && (today - info.lastPlayedDay <= 1)) {
        return RepeatBadge.ON_REPEAT
    }

    // 2. Prioridad Secundaria: Reproducciones Hoy (Flama 🔥)
    if (info.playsToday >= 3 && info.lastPlayedDay == today) {
        return RepeatBadge.HOT_TODAY
    }

    return RepeatBadge.NONE
}
```

### 4. LÓGICA DE "CANCIÓN BENDECIDA" E HISTORIAL ATÓMICO

Para proteger la integridad de los gustos del usuario, el sistema realiza una consulta pre-atómica antes de registrar un "Skip".

```
// Implementación en MusicDataStore.kt
suspend fun addToHistory(newItem: HistoryItem) {
    commitMutex.withLock {
        val currentHistory = getHistory().toMutableList()

        // Buscar si la canción ya existe en el Top 10
        val existingIndex = currentHistory.indexOfFirst { it.trackKey == newItem.trackKey }

        val finalizedItem = if (existingIndex != -1) {
            val existing = currentHistory[existingIndex]
            // REGLA DE BENDICIÓN: Si ya era "limpia", el nuevo Skip se anula
            if (!existing.isSkipped && newItem.isSkipped) {
                newItem.copy(isSkipped = false, skipStreak = 0)
            } else {
                newItem
            }
        } else {
            newItem
        }

        // Actualización de la lista: Mover al inicio (Cima del Historial)
        if (existingIndex != -1) currentHistory.removeAt(existingIndex)
        currentHistory.add(0, finalizedItem)

        // Mantener límite de 10 para evitar TransactionTooLargeException
        val trimmedHistory = currentHistory.take(10)
        saveHistoryToDataStore(trimmedHistory)
    }
}
```

### 5. CLASIFICACIÓN POR BANDAS DE FRICCIÓN

Algoritmo de decisión para determinar el resultado de una sesión de escucha basado en el ratio de finalización.

```
fun calculateOutcome(progressMs: Long, durationMs: Long): PlaybackOutcome {
    if (durationMs <= 0) return PlaybackOutcome.PARTIAL

    val ratio = progressMs.toFloat() / durationMs.toFloat()

    return when {
        ratio > 0.85f -> PlaybackOutcome.COMPLETED // Certifica como favorita
        ratio < 0.40f -> PlaybackOutcome.SKIPPED   // Aumenta racha de rechazo
        else -> PlaybackOutcome.PARTIAL            // Zona neutra
    }
}
```

### 6. LISTA NEGRA DE PRÁCTICAS (ANTI-PATRONES)

- **PROHIBIDO: Uso de `System.currentTimeMillis()` para Identidad.** El `trackKey` nunca debe depender de marcas de tiempo de llegada, ya que esto rompería la asociación con el archivo de imagen en disco si la misma canción se reproduce dos veces.
- **PROHIBIDO: Escrituras en DataStore fuera de un Mutex.** Ignorar el `commitMutex` provocará el error `Multiple DataStores active`, corrompiendo el historial de reproducciones durante ráfagas de eventos.
- **PROHIBIDO: Serialización de Bitmaps en JSON.** El historial de DataStore solo debe guardar metadatos (Strings/Longs). Los bitmaps deben persistirse como archivos `.webp` independientes referenciados por la `trackKey`.
- **PROHIBIDO: Cálculos de Analítica en Segundo Plano.** No usar `WorkManager` para resetear rachas a medianoche. La lógica debe ser "Lazy" (calculada al leer), comparando el `lastPlayedDay` con el día actual para ahorrar ciclos de CPU.
- **PROHIBIDO: Ignorar el Límite de 10 Elementos.** Superar los 10 elementos en el historial con metadatos densos puede causar `TransactionTooLargeException` en el proceso de `RemoteViews`.

---

**FIN DEL MÓDULO 3**