# REPORTE DE EVIDENCIA — AUDITORÍA FORENSE v20

## CASO A: Portadas del historial con hash inconsistente
**Incidente:** Mismatch de carátula en "Belly Breathing" y "Killing Time".

### 1. Evidencia de la Ruta de Escritura (Proactiva y en Commit)
En el Shadow Observer existen dos puntos de persistencia de imagen. Se observa una discrepancia en la construcción de la variable local `trackKey` entre estas dos funciones del archivo `MusicNotificationListener.kt`.

**Código Fuente: Persistencia Proactiva (Eager)**
```kotlin
// MusicNotificationListener.kt:942
private suspend fun persistHistoryArtworkEagerly(snapshot: MediaSnapshot) {
    withContext(Dispatchers.IO) {
        try {
            val trackKey = snapshot.trackKey // <--- Usa la propiedad del objeto MediaSnapshot
            val historyDir = File(filesDir, "history")
            val artworkFile = File(historyDir, "art_${trackKey.hashCode()}.webp")
            ...
```

**Código Fuente: Persistencia en Cierre (Commit)**
```kotlin
// MusicNotificationListener.kt:801
private suspend fun commitToHistory(sessionUUID: String, startSnapshot: MediaSnapshot, endSnapshot: MediaSnapshot) {
    try {
        val historyDir = File(filesDir, "history")
        ...
        // REGLA 1: VaultKey (T+A+D Final) para nombrar el archivo
        val trackKey = "${endSnapshot.title}|${endSnapshot.artist}|${endSnapshot.durationMs}" // <--- Construcción local manual

        val bufferDir = File(filesDir, "history/buffer")
        val artworkFile = File(historyDir, "art_${trackKey.hashCode()}.webp")
        ...
```

**Código Fuente: Definición de Identidad en MediaSnapshot**
```kotlin
// MusicNotificationListener.kt:473
val sessionIdentity: String
    get() = "$packageName|${title.trim().lowercase()}|${artist.trim().lowercase()}"

val trackKey: String
    get() = "$sessionIdentity|$album|$durationMs"
```

### 2. Evidencia de la Ruta de Lectura (Glance UI)
**Código Fuente: Renderizado de Fila de Historial**
```kotlin
// MusicWidget.kt:502
@Composable
private fun HistoryItemRow(context: Context, item: HistoryItem) {
    ...
    val cacheKey = "hist_${item.trackKey}_${item.timestamp}"
    val bitmap = bitmapCache.get(cacheKey) ?: run {
        val file = File(item.artworkPath)
        val expectedFileName = "art_${item.trackKey.hashCode()}.webp"
        val isSynchronized = file.name == expectedFileName
        ...
```

### 3. Verbatim de Logs (Caso Belly Breathing)
*   **Almacenamiento (14:14:10):** `[HISTORY_ART] Guardando imagen de historial en disco para: com.spotify.music|belly breathing|josh conway|plum|283000 -> Path: .../art_-1651305466.webp`
*   **Consolidación (14:14:40):** `[SHADOW_OBSERVER] Portada consolidada para: belly breathing|josh conway|283000 (from UUID c650398d...)`
*   **Renderizado (14:16:17):** `[GLANCE_RENDER] Dibujando ítem de historial: Title = belly breathing | State = FILE_READY | Uri = .../art_-610788208.webp`

---

## CASO B: Arranque del Sistema y Rehidratación
**Incidente:** Cambio de UUID tras reinicio en la pista "Magnet".

### 1. Código Fuente del Ciclo de Inicio
```kotlin
// MusicNotificationListener.kt:1066
override fun onListenerConnected() {
    super.onListenerConnected()
    ...
    // Registro asíncrono de rehidratación
    serviceScope.launch {
        ...
        val currentInfo = musicDataStore.musicInfoFlow.first()
        if (currentInfo.trackKey.isNotEmpty()) {
            val recoveredSnapshot = MediaSnapshot.fromPersisted(currentInfo)
            currentLogicalSession = LogicalSession(...)
            InternalLogger.d(applicationContext, "[HIST_BOOT] REHYDRATED: uuid=${recoveredSnapshot.sessionUUID}")
        }
        refreshBestSession(reason = "listener_reconnected")
    }

    // Procesamiento inmediato de sesiones activas (fuera del launch anterior)
    val initialControllers = mediaSessionManager.getActiveSessions(componentName)
    updateActiveSessions(initialControllers)
}
```

### 2. Verbatim de Logs (Caso Magnet)
*   **Pre-Reboot (20:04:06):** `FSM: Nueva Sesión Creada (UUID=14e371da-722f-4b84-9256-1c41b25ec3ef): Magnet`
*   **Post-Reboot (20:33:31):** `[INTAKE] Recibido: Estado=OTHER(6), Track=Magnet, Album=..., Duración=...`
*   **Post-Reboot (20:33:31):** `EVENT_RECEIVED: Magnet (UUID=2e16d6ab-4c78-42bf-ad66-674acba8ceb3)`

---

## CASO C: Duplicidad Visual ("Now Playing" + Historial)
**Incidente:** "Killing Time" aparece simultáneamente en el widget y la lista.

### 1. Código Fuente de la Lógica de Filtrado
```kotlin
// MusicWidget.kt:454
@Composable
private fun HistoryList(context: Context, history: List<HistoryItem>, currentTrackKey: String) {
    ...
    // FILTRO DE REDUNDANCIA VISUAL:
    val filteredHistory = if (currentTrackKey.isNotBlank()) {
        history.filter { it.trackKey != currentTrackKey }
    } else history
    ...
}
```

### 2. Evidencia de Identidad de Cruce
*   **Identidad en Pista Activa (`currentTrackKey`):** Procede de `MusicInfo.trackKey`, que se llena en `processSnapshot` usando `MediaSnapshot.trackKey` (Sanitizado: `com.spotify.music|killing time|magdalena bay|Imaginal Disk|234000`).
*   **Identidad en Historial (`item.trackKey`):** Procede de la variable local en `commitToHistory` (Unsanitizado: `Killing Time|Magdalena Bay|234000`).

---

## RESUMEN DE LA AUDITORÍA
1.  **Divergencia de Identificadores:** Se han localizado dos formas distintas de construir el `trackKey` en el código fuente.
2.  **Carrera de Arranque:** El proceso de conexión del listener lanza la recuperación de datos de forma asíncrona mientras procede a escanear sesiones vivas de forma síncrona.
3.  **Fallo de Filtrado:** El filtro de redundancia compara dos cadenas de texto (`trackKey`) generadas mediante algoritmos diferentes en Stage 1 y Stage 2.
