# Documentación Técnica: Integridad de Sesión y Sistema de Llaves Digitales

Este documento detalla el mecanismo de seguridad y sincronización que garantiza que toda la información visual del widget (portada, icono, metadatos, letras y estado) sea coherente y pertenezca a la misma sesión multimedia, evitando el "cruce de información" entre diferentes aplicaciones.

## 1. El Concepto de "Identidad de Pista" (`trackKey`)

La base de toda la validación es el `trackKey`. No confiamos solo en el título de la canción, ya que dos apps podrían reproducir una canción con el mismo nombre. Generamos una "huella digital" única en [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt):

```kotlin
val trackKey: String
    get() = buildString {
        append(packageName)
        append('|')
        append(title)
        append('|')
        append(artist)
        append('|')
        append(album.orEmpty())
        append('|')
        append(durationMs)
    }
```

Esta llave se propaga por todo el sistema y es la que decide si una pieza de información (como una letra o una portada) es "huérfana" o pertenece a la sesión actual.

---

## 2. Sincronización Visual: El Sistema de Llaves Digitales (`.key`)

Para evitar que el widget muestre, por ejemplo, la portada de Spotify con el título de YouTube Music, utilizamos un sistema de verificación en disco:

### A. Validación de Portada e Icono
Cuando descargamos una portada o icono, guardamos un archivo `.key` (`album_art.key` / `app_icon.key`) con la llave de la sesión. El widget, antes de renderizar, realiza esta comprobación:

```kotlin
val isArtworkSynchronized = displayedInfo.artworkKey.trim() == readTextFile(File(context.filesDir, ALB_KEY_FILE)).trim()
```

**Mecanismo de Limpieza Atómica (Icon Fix):** Para evitar que el icono de una app anterior se "pegue" en la nueva (ej. ver el icono de KDE Connect sobre Spotify), el sistema ahora borra la llave y el archivo del icono inmediatamente al detectar un cambio de pista, incluso si la nueva app aún no ha proporcionado su propio icono.

---

## 3. Protección del Estado y Tiempo Relativo (Allow-list)

Hemos implementado un "Portero" (Filtro Temprano) que protege la base de datos de apps no deseadas.

### A. El Filtro de Confianza
En [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt), antes de procesar cualquier evento, verificamos si la app tiene permiso:

1. **Blacklist Interna:** Bloqueo explícito de apps de sistema "ruidosas" (KDE Connect, Android Auto, System UI, Google Maps).
2. **Validación Dinámica:** Apps marcadas como Audio/Video o que declaran un `MediaBrowserService`.
3. **Lista Blanca Estática:** Reproductores conocidos (Spotify, YouTube Music, etc.).

### B. Regla de Promoción de Sesión (Session Promotion)
Para evitar que una app intrusa borre la información de la sesión actual (Snapshot), hemos implementado una guardia de estado:
- Si una nueva aplicación intenta tomar el control pero su estado es **Pausado** (Weak Session), el widget la ignora y mantiene la sesión anterior en pantalla.
- Solo si la nueva aplicación entra en estado **Reproduciendo** (Strong Session), se le permite desplazar a la anterior.

### C. Inmunidad del Reloj (`lastUpdate`)
Para que el texto "Hace X horas" sea verídico, el `MusicDataStore` protege el timestamp. Solo permite resetear el reloj si:
1. La identidad de la canción cambia (`identityChanged`).
2. Hay un "Pulso" real de reproducción (Play -> Pausa o Cierre de sesión).
3. El dispositivo de salida cambia.

Este mecanismo ahora es consciente del estado de **Sesión Latente (Snapshot)**, manteniendo el tiempo verídico incluso si la sesión del sistema se destruye.

---

## 4. Gestión de Letras Sincronizadas

Las letras son el elemento más volátil. Para asegurar que no se crucen, implementamos una **Actualización Quirúrgica** en [MusicDataStore.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt):

```kotlin
suspend fun updateLyricsOnly(lyric: String, trackKey: String) {
    context.dataStore.edit { prefs ->
        val currentTrack = prefs[TRACK_KEY] ?: ""
        if (currentTrack == trackKey) {
            prefs[CURRENT_LYRIC] = lyric
        }
    }
}
```

**La Regla de Oro:** Si el `trackKey` de la letra que llega no coincide exactamente con el `trackKey` de la canción que está guardada en la base de datos, la letra se descarta. Esto evita que, al cambiar de canción rápidamente, veas la letra de la canción anterior sobre el título de la nueva.

---

## 5. El Flujo de Reemplazo (Cambio de App)

¿Qué pasa cuando pasas de Spotify a YouTube Music?

1. **Detección:** El `MusicNotificationListener` detecta la nueva sesión de YouTube Music.
2. **Validación:** YouTube Music pasa el filtro de `isAppAllowed`.
3. **Ruptura de Identidad:** El nuevo `trackKey` es diferente al de Spotify.
4. **Limpieza:**
   - Se guarda la sesión de Spotify en el **Historial**.
   - Se descargan la nueva portada e icono.
   - Se sobrescriben los archivos `.key` en disco.
5. **Notificación:** El `MusicDataStore` detecta el cambio de identidad, resetea el `lastUpdate` a "ahora" y emite el nuevo estado.
6. **Renderizado:** El widget recibe la señal, ve que las nuevas llaves en disco coinciden, y actualiza toda la interfaz de golpe.

---

## Conclusión

El widget no es una simple pantalla pasiva; es un sistema de **validación continua**. Gracias a la combinación de `trackKey` (Identidad), archivos `.key` (Sincronización visual) y el Filtro de Confianza (Seguridad), hemos logrado que la información sea atómica: o se muestra toda la sesión correcta, o no se muestra nada, pero nunca verás información cruzada.

> [!TIP]
> Si en el futuro el widget muestra información de una app no deseada, el primer lugar a revisar es la función `isAppAllowed` en el Listener. Si muestra imágenes cruzadas, el culpable suele ser la lógica de comparación de `artworkKey` en el Widget.
