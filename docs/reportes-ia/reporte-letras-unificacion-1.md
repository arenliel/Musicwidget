# Reporte - Conjunto Letras-Unificacion-1

## 1. Compilación
```
BUILD SUCCESSFUL in 1s
```

## 2. Salida de Pruebas Unitarias (:app:testDebugUnitTest)
```
:app:testDebugUnitTest
91 passed, 0 skipped, 0 failed
```

## 3. Busqueda de currentLyrics y currentLyricsIdentity en MusicNotificationListener.kt
`git grep -nw -E "currentLyrics|currentLyricsIdentity" app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt`
```
(empty)
```

## 4. Llamadas a lyricsRepository.getLyrics en MusicNotificationListener.kt
`git grep -n "lyricsRepository.getLyrics" app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt`
```
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:3067:        val result = lyricsRepository.getLyrics(lyricsCacheKey(info), info.artist, info.title, info.durationMs) ?: return null
```

## 5. Referencias a resolveLyrics, loadedLyrics y LoadedLyrics en MusicNotificationListener.kt
`git grep -n "resolveLyrics" app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt ; git grep -n "loadedLyrics" app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt ; git grep -n "LoadedLyrics" app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt`
```
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2776:                    val loaded = resolveLyrics(freshInfo)
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2795:                // must be loaded again is decided in exactly one place: resolveLyrics.
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:3044:                val lyricsRes = resolveLyrics(currentInfo)?.lyrics ?: return@launch
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:3060:    private suspend fun resolveLyrics(info: MusicInfo): LoadedLyrics? {
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:252:    private var loadedLyrics: LoadedLyrics? = null
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:793:                    if (lyricsUpdateJob?.isActive != true && loadedLyrics != null) {
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2762:                loadedLyrics = null
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2796:                InternalLogger.d(applicationContext, "[LYRICS_RETRY_TRACE] Entrando a sincronización pasiva: currentLyricsEsNull=${loadedLyrics == null}, trackKey=${snapshot.trackKey}, durationMs=${snapshot.durationMs}")
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:2801:                InternalLogger.d(applicationContext, "[LYRICS_RETRY_TRACE] Pre-shouldResync: stateChangedUI=$stateChangedUI, drift=$drift, tickerActivo=${lyricsUpdateJob?.isActive}, currentLyricsEsNull=${loadedLyrics == null}")
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:3016:        InternalLogger.d(applicationContext, "[LYRICS_RETRY_TRACE] relaunchLyricsTicker invocado: reason=$reason, currentLyricsEsNull=${loadedLyrics == null}")
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:3057:    // network, with its own shared in-flight request) and the answer is published in loadedLyrics.
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:3062:        val cached = loadedLyrics
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:3070:        return LoadedLyrics(identity, result).also { loadedLyrics = it }
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:249:    private data class LoadedLyrics(val identity: String, val lyrics: LyricsResult)
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:252:    private var loadedLyrics: LoadedLyrics? = null
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:3060:    private suspend fun resolveLyrics(info: MusicInfo): LoadedLyrics? {
app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt:3070:        return LoadedLyrics(identity, result).also { loadedLyrics = it }
```

## 6. Busqueda de computeSessionIdentity con argumentos en MusicNotificationListener.kt
`git grep -n -E "computeSessionIdentity\(currentRAM|computeSessionIdentity\(freshInfo|computeSessionIdentity\(currentInfo|computeSessionIdentity\(info" app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt`
```
(empty)
```

## 7. Mensaje de commit
```
Conjunto Letras-Unificacion-1: una sola funcion decide si reutilizar o cargar la letra de la cancion que suena; la letra cargada y su identidad viajan en un unico objeto inmutable; la identidad de negocio sale de MusicInfo.sessionIdentity
```
