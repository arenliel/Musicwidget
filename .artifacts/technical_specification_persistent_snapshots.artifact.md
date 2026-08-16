# Especificación Técnica: Snapshots Persistentes (Sesión Latente)

Este documento detalla la implementación técnica de la función "Snapshot", que permite al Music Widget mantener la identidad visual de la última canción reproducida incluso después de que el sistema operativo Android haya destruido la sesión multimedia activa.

## 1. Concepto y Propósito

El "Snapshot" es un estado de **Persistencia Pasiva**. Su objetivo es evitar que el widget muestre un estado vacío ("¡Reproduce algo!") de forma prematura. Actúa como un puente entre la sesión en tiempo real y el historial de reproducción.

---

## 2. Flujo de Captura y Creación

La magia ocurre en [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt).

### A. Detección de Fin de Sesión
Cuando cierras una aplicación de música (ej. deslizas la tarjeta en el panel de notificaciones), el sistema dispara el callback `onSessionDestroyed`. El listener reacciona así:

```kotlin
// Línea ~465 en MusicNotificationListener.kt
override fun onSessionDestroyed() {
    if (selectedController?.sessionToken == controller.sessionToken) {
        selectedController = null
    }
    requestRefresh(reason = "session_destroyed")
}
```

### B. Congelación del Estado (The Snapshot)
En `refreshBestSession`, si no hay sesiones vivas, no borramos los datos. En su lugar, tomamos el `lastAppliedSnapshot` (la última canción que el widget mostró con éxito) y creamos una copia "congelada":

```kotlin
// Línea ~540 en MusicNotificationListener.kt
if (activeSessions.isEmpty()) {
    selectedController = null

    lastAppliedSnapshot?.let { last ->
        val snapshot = last.copy(
            playbackState = PlaybackState.STATE_NONE, // Detenemos el motor de progreso
            isSessionActive = false                   // Marcamos como latente
        )
        processSnapshot(null, null, snapshot, "no_active_sessions")
    }
    return
}
```

---

## 3. Integración con el Estado de Reproducción (Status Text)

La relación entre el Snapshot y el texto de "Reciente, hace X horas" se gestiona en [MusicDataStore.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt).

### A. Protección del Timestamp (`lastUpdate`)
Para que el tiempo sea verídico, el `MusicDataStore` decide si debe resetear el reloj. Al pasar a modo Snapshot (`isSessionActive` de `true` a `false`), el sistema detecta un "Cambio de Pulso" y guarda el momento exacto del cierre:

```kotlin
// Línea ~414 en MusicDataStore.kt
val playbackStatusChanged = currentIsPlaying != info.isPlaying ||
        (currentIsSessionActive && !info.isSessionActive) || // <--- Trigger del Snapshot
        currentPlaybackDeviceName != info.playbackDeviceName

if (identityChanged || playbackStatusChanged || forceUpdate) {
    prefs[LAST_UPDATE] = System.currentTimeMillis()
}
```

### B. Visualización en la UI
En [MusicWidget.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicWidget.kt), la función `getStatusText` detecta que la sesión no está activa (`isSessionActive == false`) y activa el modo de tiempo relativo:

```kotlin
// Línea ~393 en MusicWidget.kt
private fun getStatusText(context: Context, info: MusicInfo): String = when {
    info.isPlaying -> context.getString(R.string.status_listening)
    info.isSessionActive -> context.getString(R.string.status_paused)
    else -> {
        val time = formatRelativeTime(context, info.lastUpdate)
        if (time.isEmpty()) context.getString(R.string.status_recently) else time
    }
}
```

---

## 4. El "Escudo" de la Sesión (Promotion Logic)

¿Qué pasa si una app "intrusa" (como KDE Connect) abre una sesión mientras tenemos un Snapshot de Spotify? Aquí es donde entra la **Regla de Promoción de Sesión**:

```kotlin
// Línea ~985 en MusicNotificationListener.kt
if (trackChanged && previousSnapshot != null && snapshot.packageName != previousSnapshot.packageName) {
    val isNewSessionWeak = snapshot.playbackState != PlaybackState.STATE_PLAYING
    if (isNewSessionWeak) {
        Log.d(TAG, "[DIAGNOSTIC] IGNORED: Ignorando sesión débil de ${snapshot.packageName} para mantener Snapshot de ${previousSnapshot.packageName}")
        return // Mantenemos el Snapshot actual
    }
}
```

**Respuesta a preguntas clave:**
- **¿Se limpia el snapshot al iniciar una nueva sesión?** Sí. En el momento en que una nueva app entra en estado `STATE_PLAYING`, el código de arriba permite que la nueva información pase. El snapshot anterior es reemplazado y se envía automáticamente al **Historial de Reproducción**.
- **¿Es eficiente?** Totalmente. Una vez que el snapshot se guarda en el DataStore, el `MusicNotificationListener` detiene sus corrutinas de letras (`lyricsUpdateJob?.cancel()`) y entra en un estado de espera pasiva.

---

## 5. Relación con otras funciones

1.  **Historial:** El Snapshot actúa como una "sala de espera". Una canción solo se considera "terminada" y lista para el historial cuando es desplazada por una nueva reproducción real.
2.  **Letras (Lyrics Showcase):** Al detectar `isSessionActive == false`, el motor de letras se apaga tras un periodo de gracia, evitando que el widget intente sincronizar letras de una canción que ya no está sonando.
3.  **Sistema de Llaves:** El Snapshot utiliza la misma `trackKey` (incluyendo duración) que la sesión en vivo, asegurando que la portada en pantalla sea siempre la correcta.

---
*Documentación técnica de persistencia - Music Widget Project.*
