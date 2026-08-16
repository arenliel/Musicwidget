
# PLAN MAESTRO DE DOCUMENTACIÓN TÉCNICA: MUSIC WIDGET (VERSION 1.0)

## MÓDULO 4: INTERCEPCIÓN MULTIMEDIA Y RESOLUCIÓN DE ASSETS

Este módulo describe la capa de servicios de escucha de notificaciones, la integración con sesiones multimedia activas, el motor de traducción de activos de red (Spotify CDN) y el sistema de rescate y normalización de iconos.

### 1. ESPECIFICACIONES TÉCNICAS DE INTERCEPCIÓN
| Parámetro | Valor / Estrategia | Justificación Técnica |
| :--- | :--- | :--- |
| **Servicio Base** | `NotificationListenerService` | Única vía persistente para capturar metadatos y portadas en Android 11+. |
| **Resolución de Arte** | Jerarquía de 3 Niveles | 1. Metadata Directo -> 2. Notificación (LargeIcon) -> 3. Red (CDN). |
| **Time-out de Red** | 7000ms (7.0s) | Garantiza el éxito de descarga en redes móviles (H+/3G) lentas. |
| **Peso Visual (Iconos)** | 72% (Reference Ink Ratio) | Normaliza el tamaño percibido entre glifos vectoriales y bitmaps circulares. |
| **Sincronía de Assets** | Sistema de Llaves `.key` | Valida que el archivo binario en disco pertenezca al `trackKey` activo antes de renderizar. |

### 2. MOTOR DE TRADUCCIÓN DE ACTIVOS (SPOTIFY CDN)
Spotify bloquea el acceso directo a sus URIs internas (`content://`). El sistema intercepta estas peticiones y las traduce a la infraestructura de red pública de Spotify.

```kotlin
/**
 * Traduce URIs privadas de Spotify a URLs públicas de alta resolución.
 * Soporta metadatos de hasta 1000px para evitar saturación de memoria en widgets.
 */
fun translateSpotifyUri(uri: String): String {
    return when {
        uri.startsWith("content://com.spotify.mobile.android.mediaapi") -> {
            val trackId = uri.substringAfterLast("/")
            "https://i.scdn.co/image/\$trackId"
        }
        uri.startsWith("spotify:image:") -> {
            val trackId = uri.substringAfter("spotify:image:")
            "https://i.scdn.co/image/\$trackId"
        }
        else -> uri
    }
}
````

### 3. SISTEMA DE RESCATE DE ICONOS Y ASCENSO DE TIER

Para garantizar que el widget siempre muestre el mejor icono posible, se implementa una jerarquía de calidad que se actualiza reactivamente.

```
// Niveles de fidelidad para el icono de aplicación
enum class IconTier {
    NOTIFICATION, // Máxima calidad (extraído de la barra de estado)
    MONOCHROME,   // Rescate (capa monochrome de Adaptive Icon)
    COLOR_FALLBACK // Último recurso (getApplicationIcon original)
}

/**
 * Lógica de "Ascenso de Tier": Si llega una notificación nítida de una app
 * que ya está activa pero tenía un icono de baja calidad, se actualiza el widget.
 */
fun onNotificationPosted(sbn: StatusBarNotification) {
    val currentPkg = currentMetadata.packageName
    if (sbn.packageName == currentPkg && currentIconTier < IconTier.NOTIFICATION) {
        val navIcon = sbn.notification.getLargeIcon() ?: sbn.notification.smallIcon
        navIcon?.let {
            saveIconAtomatic(it, IconTier.NOTIFICATION)
            triggerUiUpdate()
        }
    }
}
```

### 4. ALGORITMO DE NORMALIZACIÓN POR SILUETA (IMAGEUTILS)

Este algoritmo elimina el "Efecto Icono Diminuto" causado por los márgenes transparentes de los iconos adaptativos de Android.

```
/**
 * Escanea el canal alfa del icono, lo recorta a su silueta real y lo
 * expande al 72% del área para igualar el peso visual de la interfaz.
 */
fun getSilhouetteNormalizedBitmap(source: Bitmap): Bitmap {
    val width = source.width
    val height = source.height

    // 1. Detectar límites reales (Bounding Box del canal Alfa)
    var top = height; var bottom = 0; var left = width; var right = 0
    for (y in 0 until height) {
        for (x in 0 until width) {
            if ((source.getPixel(x, y) shr 24) and 0xff > 10) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
    }

    val glyphWidth = right - left
    val glyphHeight = bottom - top
    if (glyphWidth <= 0 || glyphHeight <= 0) return source

    // 2. Crear recorte cuadrado centrado en la masa del glifo
    val size = maxOf(glyphWidth, glyphHeight)
    val cropped = Bitmap.createBitmap(source, left, top, glyphWidth, glyphHeight)

    // 3. Escalado final al ratio de tinta de referencia (0.72)
    val finalSize = (source.width * 0.72f).toInt()
    return Bitmap.createScaledBitmap(cropped, finalSize, finalSize, true)
}
```

### 5. INTEGRIDAD VISUAL VÍA DISCO (ATOMIC SYNC)

Para evitar que el widget muestre una portada vieja sobre una canción nueva, se utiliza un sistema de verificación cruzada mediante archivos de texto `.key`.

```
/**
 * Procedimiento de guardado atómico de activos multimedia.
 * Se asegura de que el archivo binario y su llave de identidad se escriban juntos.
 */
private suspend fun saveAssetAtomatic(context: Context, bitmap: Bitmap, key: String, type: String) {
    fileMutex.withLock {
        val binFile = File(context.cacheDir, "\$type.webp")
        val keyFile = File(context.cacheDir, "\$type.key")
        val tempFile = File(context.cacheDir, "\$type.tmp")

        // Escritura del binario (WebP Lossy 80% para evitar TransactionTooLarge)
        FileOutputStream(tempFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.WEBP, 80, out)
        }

        if (tempFile.exists()) {
            tempFile.renameTo(binFile) // Movimiento atómico
            keyFile.writeText(key)      // Certificación de la llave
        }
    }
}
```

### 6. LISTA NEGRA DE PRÁCTICAS (ANTI-PATRONES)

- **PROHIBIDO: `getActiveNotifications()` vía llaves.** No se debe confiar en las llaves de notificación (`Notification.key`) ya que no son persistentes entre reinicios del servicio; usar siempre filtrado por `packageName`.
- **PROHIBIDO: Escritura Directa en Archivos Finales.** Jamás escribir directamente en `album_art.webp`. Siempre usar el patrón `.tmp` para prevenir el error de Skia en Glance (PNG unsigned integer out of range).
- **PROHIBIDO: Ignorar el Límite de 1MB de Binder.** No intentar pasar Bitmaps de más de 600px a través del historial de `RemoteViews`. Siempre escalar a 120px para miniaturas.
- **PROHIBIDO: Consultas AudioManager en cada Cambio.** No consultar el hardware de audio en cada canción. Usar el sistema de `AudioDeviceCallback` para actualizar el caché solo cuando el usuario conecte/desconecte físicamente un dispositivo.
- **PROHIBIDO: `android:tint` en XML de Vectores.** Los iconos de dispositivo (Bluetooth, Altavoz) no deben tener tintado fijo en el XML, ya que Glance no puede sobrescribirlo dinámicamente con Material You si el atributo está quemado.

---

**FIN DEL MÓDULO 4**

```

He configurado este módulo unificando el sistema de "Ascenso de Tier" (fuentes) y el algoritmo de normalización por silueta (fuente). ¿Deseas que procedamos con el **Módulo 5: Compatibilidad Multi-API y Previsualizaciones XML**?
```