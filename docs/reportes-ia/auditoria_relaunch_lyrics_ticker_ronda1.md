# AUDITORÍA — relaunchLyricsTicker (RONDA 1)

## Paso 0 — Confirmación de HEAD
```text
1b5a9bb911d11b0efe3ea63f1dd1121c6f1a8560
```

## Paso 0.5 — Confirmación de identidad de archivo
`head -n 5 app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt`:
```kotlin
package arenliel.musicwidget

import android.app.Notification
import android.appwidget.AppWidgetManager
import android.content.ComponentName
```

## Paso 1 — Localización (`grep -n "fun relaunchLyricsTicker" -r .`)
```text
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2829:    private fun relaunchLyricsTicker(reason: String) {
old_mnl.kt:2594:    private fun relaunchLyricsTicker(reason: String) {
```

## Paso 2 — Cuerpo verbatim completo de `relaunchLyricsTicker`
```kotlin
    private fun relaunchLyricsTicker(reason: String) {
        InternalLogger.d(applicationContext, "[LYRICS_RETRY_TRACE] relaunchLyricsTicker invocado: reason=$reason, currentLyricsEsNull=${currentLyrics == null}")
        if (!isWidgetPotentiallyVisible()) {
            synchronized(lyricsLock) {
                lyricsUpdateJob?.cancel()
            }
            return
        }
        val currentInfo = MusicStateProvider.current()
        if (currentInfo.isEmpty || !currentInfo.isSessionActive) {
            synchronized(lyricsLock) {
                lyricsUpdateJob?.cancel()
            }
            return
        }
        InternalLogger.d(applicationContext, "[LYRICS_TRACE] relaunchLyricsTicker: Reason=$reason | Track=${currentInfo.title}")

        val activeSession = currentLogicalSession ?: return

        // Conjunto Letras-Atomicas-2: identidad de negocio de lo que suena AHORA. Es la única
        // llave que decide si podemos reutilizar `currentLyrics` sin tocar red/disco. Capturamos
        // ambos valores aquí (fuera de la corrutina) para no leer variables de clase que puedan
        // cambiar mientras la corrutina está suspendida.
        val targetIdentity = MusicDataStore.computeSessionIdentity(currentInfo.packageName, currentInfo.title, currentInfo.artist)
        val cachedLyrics = currentLyrics
        val canReuse = cachedLyrics != null && currentLyricsIdentity == targetIdentity
        InternalLogger.d(applicationContext, "[LYRICS_RETRY_TRACE] Decisión de datos: reused=$canReuse, targetIdentity=$targetIdentity, cachedIdentity=$currentLyricsIdentity")

        synchronized(lyricsLock) {
            lyricsUpdateJob?.cancel()
            lyricsUpdateJob = activeSession.lyricsScope.launch {
                val lyricsRes = if (canReuse && cachedLyrics != null) {
                    // Ya tenemos la letra correcta para esta identidad exacta: nos ahorramos el
                    // viaje a disco/red. Esto es lo que vuelve inofensivo que varios disparadores
                    // (Stage 1, sincronización pasiva, screen_wake, seek_event) llamen a esta
                    // función casi al mismo tiempo para el mismo evento — todos convergen aquí sin
                    // competir por una descarga lenta que terminan cancelándose entre sí.
                    cachedLyrics
                } else {
                    lyricsRepository.getLyrics(
                        currentInfo.trackKey, currentInfo.artist, currentInfo.title, currentInfo.durationMs
                    )?.also {
                        currentLyrics = it
                        currentLyricsIdentity = targetIdentity
                    } ?: return@launch
                }
                if (currentInfo.isPlaying) {
                    runLyricsShowcase(targetIdentity, lyricsRes)
                } else {
                    runPausedLyricsCycle(targetIdentity, lyricsRes)
                }
            }
        }
    }
```

## Paso 3 — Variables compartidas relevantes

`grep -n "currentLyrics\b" -r .`:
```text
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:236:    private var currentLyrics: LyricsResult? = null
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:238:    // que se obtuvo `currentLyrics`. Es la única llave válida para decidir si se puede reutilizar
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:674:            if (lyricsUpdateJob?.isActive != true && currentLyrics != null) {
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2588:                currentLyrics = null
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2605:                        currentLyrics = result
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2621:                InternalLogger.d(applicationContext, "[LYRICS_RETRY_TRACE] Entrando a sincronización pasiva: currentLyricsEsNull=${currentLyrics == null}, trackKey=${snapshot.trackKey}, durationMs=${snapshot.durationMs}")
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2626:                InternalLogger.d(applicationContext, "[LYRICS_RETRY_TRACE] Pre-shouldResync: stateChangedUI=$stateChangedUI, drift=$drift, tickerActivo=${lyricsUpdateJob?.isActive}, currentLyricsEsNull=${currentLyrics == null}")
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2830:        InternalLogger.d(applicationContext, "[LYRICS_RETRY_TRACE] relaunchLyricsTicker invocado: reason=$reason, currentLyricsEsNull=${currentLyrics == null}")
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2849:        // llave que decide si podemos reutilizar `currentLyrics` sin tocar red/disco. Capturamos
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2853:        val cachedLyrics = currentLyrics
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2871:                        currentLyrics = it
old_mnl.kt:212:    private var currentLyrics: LyricsResult? = null
old_mnl.kt:641:            if (lyricsUpdateJob?.isActive != true && currentLyrics != null) {
old_mnl.kt:2417:                currentLyrics = null
old_mnl.kt:2425:                currentLyrics = result
old_mnl.kt:2435:                if (currentLyrics == null) {
old_mnl.kt:2436:                currentLyrics = lyricsRepository.getLyrics(snapshot.trackKey, snapshot.artist, snapshot.title, snapshot.durationMs)
old_mnl.kt:2445:                if (shouldResync && currentLyrics != null) {
```

`grep -n "var currentLyrics" -r .`:
```text
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:236:    private var currentLyrics: LyricsResult? = null
old_mnl.kt:212:    private var currentLyrics: LyricsResult? = null
```

`grep -n "lyricsUpdateJob" -r .`:
```text
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:243:    private var lyricsUpdateJob: Job? = null
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:436:            lyricsUpdateJob?.cancel()
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:669:            lyricsUpdateJob?.cancel()
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:674:            if (lyricsUpdateJob?.isActive != true && currentLyrics != null) {
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2501:        lyricsUpdateJob?.cancel()
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2586:                lyricsUpdateJob?.cancel()
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2626:                InternalLogger.d(applicationContext, "[LYRICS_RETRY_TRACE] Pre-shouldResync: stateChangedUI=$stateChangedUI, drift=$drift, tickerActivo=${lyricsUpdateJob?.isActive}, currentLyricsEsNull=${currentLyrics == null}")
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2627:                val shouldResync = stateChangedUI || drift > 1500L || lyricsUpdateJob?.isActive != true
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2833:                lyricsUpdateJob?.cancel()
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2840:                lyricsUpdateJob?.cancel()
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2858:            lyricsUpdateJob?.cancel()
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2859:            lyricsUpdateJob = activeSession.lyricsScope.launch {
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2895:            lyricsUpdateJob?.cancel()
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:3489:            lyricsUpdateJob?.cancel()
old_mnl.kt:213:    private var lyricsUpdateJob: Job? = null
old_mnl.kt:403:            lyricsUpdateJob?.cancel()
old_mnl.kt:636:            lyricsUpdateJob?.cancel()
old_mnl.kt:641:            if (lyricsUpdateJob?.isActive != true && currentLyrics != null) {
old_mnl.kt:2343:            lyricsUpdateJob?.cancel()
old_mnl.kt:2415:            lyricsUpdateJob?.cancel()
old_mnl.kt:2443:            val shouldResync = stateChangedUI || drift > 1500L || lyricsUpdateJob?.isActive != true
old_mnl.kt:2596:            lyricsUpdateJob?.cancel()
old_mnl.kt:2602:            lyricsUpdateJob?.cancel()
old_mnl.kt:2607:            lyricsUpdateJob?.cancel()
old_mnl.kt:2610:            lyricsUpdateJob = serviceScope.launch(Dispatchers.IO) {
old_mnl.kt:2634:            lyricsUpdateJob?.cancel()
old_mnl.kt:3199:            lyricsUpdateJob?.cancel()
```
