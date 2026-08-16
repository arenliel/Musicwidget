# Expediente Técnico de Arquitectura: Resolución de Identidad Multimedia, Normalización por Silueta y Sistema de Rescate de Iconos en Music Widget

**Fecha de inicio del chat:** 06 de Agosto de 2026

Este documento constituye la memoria técnica exhaustiva de la sesión de trabajo orientada a perfeccionar la integridad visual del Music Widget, con especial énfasis en la resolución de los problemas de obtención de carátulas e iconos tintados para la aplicación de YouTube, así como la implementación de algoritmos de normalización de imagen para garantizar un peso visual coherente en toda la interfaz.

---

## 1. Contexto Inicial y Arquitectura de Sesión (06 de Agosto)

La conversación inició con la necesidad de documentar el sistema de obtención de la portada de la canción actual, la cual se presenta enmarcada en una forma de "píldora" rotada a -28 grados en los tamaños estándar, o abarcando todo el fondo en el modo de "portada completa".

### El Sistema de Llaves Digitales (`trackKey`)
Para garantizar que el widget no muestre información cruzada (ej. el título de Spotify con la imagen de YouTube), se estableció que la base de la validación es el `trackKey`.

**Implementación de la llave en `MusicNotificationListener.kt`:**
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

### Sincronización Visual vía Disco
Se definió el uso de archivos `.key` (`album_art.key` / `app_icon.key`) para certificar que los archivos binarios en disco pertenecen a la sesión activa antes de que el widget los renderice.

**Lógica de validación en `MusicWidget.kt`:**
```kotlin
val isArtworkSynchronized = displayedInfo.artworkKey.trim() == readTextFile(File(context.filesDir, ALB_KEY_FILE)).trim()
```

---

## 2. El Desafío de YouTube: Diagnóstico e Investigación (07 de Agosto)

El usuario reportó que el widget no detectaba las miniaturas de los vídeos de la aplicación principal de YouTube y que el icono de la aplicación aparecía a color (fallback) en lugar de tintado con Material You, a diferencia de la competencia.

### Hallazgos del Diagnóstico (Sondas de Logs)
Tras inyectar sondas de inspección profunda (`[DEBUG_YT]` y `[DEBUG_YT_NOTIF]`), se llegó a las siguientes conclusiones técnicas:

1.  **Metadatos Vacíos**: YouTube no envía Bitmaps ni URIs en `MediaMetadata` para evitar exceder el límite de 1MB de Binder en Android.
    *   *Log de hallazgo:* `[DEBUG_YT] Metadata keys: ARTIST, VIDEO_HEIGHT_PX, DURATION, VIDEO_WIDTH_PX, TITLE, ALBUM_ARTIST` (No hay carátula).
2.  **Notificación Invisible**: YouTube suprime su notificación de barra de estado cuando la app está en primer plano.
    *   *Log de hallazgo:* `[DEBUG_YT_NOTIF]` no mostraba ninguna notificación bajo el paquete `com.google.android.youtube`.
3.  **Capa Monocromática**: Se detectó que la app de YouTube **sí posee** una capa `monochrome` en su icono adaptativo.
    *   *Log de hallazgo:* `[DEBUG_YT] Icono Adaptativo de com.google.android.youtube: monochrome=true`.

### Decisiones Descartadas
*   **API de Invidious**: Se consideró buscar el canal del artista mediante esta API para obtener su foto de perfil. Se descartó por ser YouTube una app de vídeos (no puramente musical) y por la inestabilidad de las instancias comunitarias.
*   **Construcción de URL vía Video ID**: Se descartó porque YouTube tampoco comparte el Video ID en los metadatos de la sesión de forma fiable.

---

## 3. El Sistema de Rescate de Iconos (Implementación Senior)

Se diseñó una jerarquía de tres niveles para garantizar que el widget siempre muestre el mejor icono posible, integrándose en el sistema de integridad de llaves.

### La Jerarquía de Prioridades
1.  **Notificación**: Si existe `smallIcon` en la notificación, se usa (máxima fidelidad).
2.  **Rescate Monocromático**: Si falla el anterior, se extrae la capa `monochrome` del icono adaptativo de la app.
3.  **Color Fallback**: Si no hay capa monocromática, se muestra el icono original a color.

### Algoritmo de Normalización por Silueta (Código Final)
Para evitar que el icono monocromático se viera "minúsculo" debido a los 18dp de margen transparente de Android, se implementó un algoritmo dinámico que escanea el canal alfa y añade un padding de balance visual del 10%.

**Funciones en `MusicNotificationListener.kt`:**
```kotlin
private fun resolveAppIcon(packageName: String): Bitmap? {
    return try {
        val notifications = getActiveNotifications()
        val targetToken = selectedController?.sessionToken

        // PRIORIDAD 1: Icono de la Notificación (Vía rápida)
        val mediaNotif = if (targetToken != null) {
            notifications.firstOrNull { sbn ->
                val token = sbn.notification.extras.getParcelable<android.media.session.MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
                token == targetToken
            }
        } else {
            notifications.firstOrNull {
                it.packageName == packageName && it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
            } ?: notifications.firstOrNull { it.packageName == packageName }
        }

        val iconFromNotif = mediaNotif?.notification?.smallIcon?.loadDrawable(this)?.toBitmap()
        if (iconFromNotif != null) return iconFromNotif

        // PRIORIDAD 2: Rescate Monocromático con Algoritmo de Silueta
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val appIcon = packageManager.getApplicationIcon(packageName)
            if (appIcon is android.graphics.drawable.AdaptiveIconDrawable) {
                val monochrome = appIcon.monochrome
                if (monochrome != null) {
                    return getUnpaddedMonochromeIcon(monochrome)
                }
            }
        }

        null
    } catch (e: Exception) {
        Log.e(TAG, "Error en jerarquía de resolución de icono para $packageName", e)
        null
    }
}

private fun getUnpaddedMonochromeIcon(drawable: android.graphics.drawable.Drawable): Bitmap {
    val density = applicationContext.resources.displayMetrics.density
    val totalSize = (108 * density).toInt()

    // 1. Renderizar el lienzo completo de 108dp
    val fullBitmap = Bitmap.createBitmap(totalSize, totalSize, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(fullBitmap)
    drawable.setBounds(0, 0, totalSize, totalSize)
    drawable.draw(canvas)

    // 2. Aplicar "Silhouette Trimming": detectar píxeles reales y recortar al máximo
    val trimmedBitmap = ImageUtils.trimTransparency(fullBitmap)

    // 3. Añadir un margen de "respiración" del 10% para peso visual uniforme
    return addVisualPadding(trimmedBitmap, 0.10f)
}

private fun addVisualPadding(source: Bitmap, paddingPercent: Float): Bitmap {
    val w = source.width
    val h = source.height
    val extraW = (w * paddingPercent).toInt()
    val extraH = (h * paddingPercent).toInt()

    val newW = w + (extraW * 2)
    val newH = h + (extraH * 2)

    val output = Bitmap.createBitmap(newW, newH, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(output)
    canvas.drawBitmap(source, extraW.toFloat(), extraH.toFloat(), null)

    if (source !== output) source.recycle()
    return output
}
```

**Modificación en `ImageUtils.kt` (Función Pública):**
```kotlin
fun trimTransparency(bitmap: Bitmap): Bitmap {
    var minX = bitmap.width; var minY = bitmap.height
    var maxX = -1; var maxY = -1

    for (y in 0 until bitmap.height) {
        for (x in 0 until bitmap.width) {
            if (Color.alpha(bitmap.getPixel(x, y)) > 0) {
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
    }

    if (maxX < minX || maxY < minY) return bitmap

    val cropped = Bitmap.createBitmap(bitmap, minX, minY, maxX - minX + 1, maxY - minY + 1)
    if (cropped != bitmap) {
        bitmap.recycle()
    }
    return cropped
}
```

---

## 4. Optimizaciones de Batería y Solución de Alternancia Visual

Durante las pruebas se detectó una alternancia (flicker) entre el icono monocromático y el de color al cambiar entre vídeos de una misma app.

### El Error de Lógica Identificado
El sistema borraba la llave del icono al detectar un cambio de **Pista** (`trackChanged`), pero no intentaba recuperarlo si la **App** era la misma para ahorrar energía. Esto creaba un "vacío de llave" que causaba el parpadeo.

### La Solución de Determinismo de Sesión
Se re-definió el icono como un **"Activo de Aplicación"** (estable) y la portada como un **"Activo de Pista"** (volátil).

**Pipeline Atómico corregido en `MusicNotificationListener.kt`:**
```kotlin
// Detección de cambio de contexto
val trackChanged = previousSnapshot?.trackKey != rawSnapshot.trackKey
val appChanged = previousSnapshot?.packageName != rawSnapshot.packageName

// ... dentro del commitMutex.withLock ...

if (resolvedAppIcon != null && resolvedIconKey != null) {
    saveBitmapToFile(resolvedAppIcon, APP_ICON_FILE)
    saveTextToFile(resolvedIconKey, APP_ICON_KEY_FILE)
    savedAppIconKey = resolvedIconKey
} else if (appChanged) {
    // FIX CRÍTICO: Solo borramos la llave si la APP cambió físicamente.
    // Esto mantiene el icono estable entre vídeos de la misma aplicación.
    saveTextToFile("", APP_ICON_KEY_FILE)
    savedAppIconKey = null
}
```

---

## 5. Resumen de Decisiones de Arquitectura

| Pregunta / Problema | Solución Acordada | Justificación Arquitectónica |
| :--- | :--- | :--- |
| ¿Cómo obtener carátula de YouTube? | Ninguna (Descartado). | YouTube no es una app musical y bloquea el acceso por diseño. Complejidad excesiva para bajo valor. |
| ¿Por qué el icono es pequeño? | Algoritmo de Silueta dinámico. | Elimina la dependencia de números mágicos (86dp) y se adapta a la realidad de los píxeles de cualquier app. |
| ¿Por qué hay parpadeo? | Guarda por `appChanged`. | El icono debe persistir mientras la "Sesión de App" sea la misma, independientemente de la canción. |
| ¿Impacto en batería? | Caching agresivo. | El escaneo de silueta ocurre una sola vez al cambiar de aplicación y nunca más. |

---

## Conclusión del Expediente
Hemos evolucionado el widget de ser reactivo a eventos aislados a ser un gestor de **Sesiones Multimedia Inteligentes**. El sistema ahora distingue entre la volatilidad de la pista (portada, título) y la estabilidad de la aplicación (icono, tintado), logrando una experiencia visual fluida, profesional y optimizada para el ecosistema Android 13+.
