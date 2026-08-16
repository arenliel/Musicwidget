# Especificación Técnica: Pipeline de Obtención y Renderizado de Portadas

Este documento detalla el ciclo de vida completo de la portada (artwork) en el Music Widget, desde su detección en la sesión multimedia hasta su renderizado final con máscaras geométricas en el widget de Glance.

## 1. Arquitectura de Identidad: El Sistema de Llaves Digitales

Para garantizar la integridad visual y evitar que se muestre una portada de una sesión anterior o de una aplicación distinta (cross-info), el sistema utiliza un esquema de llaves dobles.

### A. `trackKey` (Identidad de Pista)
Generada en [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt), es un hash robusto que identifica univocamente la canción actual:
```kotlin
val trackKey: String = "$packageName|$title|$artist|$album|$durationMs"
```

### B. `artworkKey` (Identidad Visual)
Es la llave que vincula la información de la base de datos con el archivo físico en disco.
- Si la app provee una URI (ej. Spotify CDN), la llave es la propia URI.
- Si no hay URI, se utiliza el `trackKey` como fallback.

---

## 2. Fase de Adquisición (Discovery)

El `MusicNotificationListener` actúa como el motor de búsqueda. Cuando detecta un cambio de metadatos, inicia la función `findRealAlbumArt`, que busca la imagen en este orden de prioridad estricto:

1. **Metadata Directa (Offline):** `METADATA_KEY_ART`, `METADATA_KEY_ALBUM_ART` o `METADATA_KEY_DISPLAY_ICON`. Estos son Bitmaps que la aplicación inyecta directamente en memoria.
2. **Intercepción de Notificación:** Extrae el `LargeIcon` o el `EXTRA_PICTURE` de la notificación de tipo `CATEGORY_TRANSPORT`.
3. **Resolución de URIs (Online/Local):**
   - **Spotify:** Traduce prefijos `content://` a URLs directas de su CDN (`https://i.scdn.co/image/...`).
   - **Network:** Descarga imágenes vía HTTP/HTTPS.
   - **ContentResolver:** Accede a URIs locales de otras apps.

> [!TIP]
> **Capacidad Offline:** El widget prioriza siempre los Bitmaps en memoria sobre las URIs. Esto permite que el widget muestre portadas instantáneamente para música local o apps que no proporcionan una URL de imagen, eliminando la latencia de red.

> [!IMPORTANT]
> **Optimización de Memoria:** Todas las imágenes se redimensionan a un máximo de **1024x1024px** (`MAX_ART_DIMENSION`) durante la descarga para evitar errores de `TransactionTooLargeException` en los RemoteViews.

---

## 3. Fase de Transformación y Persistencia

Una vez obtenido el Bitmap "RAW", el sistema realiza una bifurcación para alimentar los diferentes modos del widget. Esto ocurre en `saveBitmapToFile`:

### A. Almacenamiento RAW (`album_art_raw.webp`)
Se guarda el bitmap cuadrado original. Este se utiliza para el modo **"Full Cover"** (portada completa) y para la actividad de detalle.

### B. Transformación "Píldora Rotada" (`album_art.webp`)
Utiliza [ImageUtils.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/ImageUtils.kt) para aplicar la estética de marca:
- **Forma:** Rectángulo redondeado con radio igual a la mitad de su altura (`pillHeight / 2`).
- **Rotación:** **-28 grados**.
- **Ajuste:** La función `trimTransparency` recorta los bordes vacíos tras la rotación para que el widget pueda posicionar la imagen de forma determinista.

```kotlin
// Ref: ImageUtils.kt
val path = Path()
val rect = RectF(-pillWidth / 2f, -pillHeight / 2f, pillWidth / 2f, pillHeight / 2f)
path.addRoundRect(rect, radius, radius, Path.Direction.CW)
canvas.clipPath(path)
canvas.rotate(rotationDegrees) // -28f
```

### C. Commit Atómico de Llave (`album_art.key`)
Inmediatamente después de escribir los archivos `.webp`, se escribe el `artworkKey` en un archivo de texto plano. Este es el "sello de garantía" que el widget verificará después.

---

## 4. Fase de Renderizado en el Widget

En [MusicWidget.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicWidget.kt), el componente Glance realiza la validación final antes de pintar:

### El "Check de Integridad"
```kotlin
val isArtworkSynchronized = displayedInfo.artworkKey.trim() ==
    readTextFile(File(context.filesDir, "album_art.key")).trim()
```

Si `isArtworkSynchronized` es `true`:
1. **Layout Estándar/Largo:** Carga `album_art.webp` (Píldora).
2. **Layout Full Cover:** Carga `album_art_raw.webp` (Cuadrado).
3. **Cache:** Se almacena en un `LruCache` de memoria para que el redibujado (ej. por progreso de barra) no re-lea el disco.

Si la validación falla (ej. porque la llave en el DataStore es nueva pero el archivo en disco aún no se ha escrito), el widget muestra un **Placeholder** (`ic_preview_pill`) para evitar parpadeos con la imagen de la canción anterior.

---

## 5. Resumen de Archivos Involucrados

| Archivo | Función Técnica |
| :--- | :--- |
| [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt) | Orquestador: Busca, descarga y coordina el guardado. |
| [ImageUtils.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/ImageUtils.kt) | Matemáticas visuales: Crea la máscara de píldora y la rotación de 28°. |
| [MusicWidget.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicWidget.kt) | Consumidor: Valida llaves y selecciona el archivo según el tamaño. |
| `album_art.webp` | Imagen procesada (Píldora rotada) para uso diario. |
| `album_art_raw.webp` | Imagen original (Cuadrada) para fondo inmersivo. |
| `album_art.key` | Llave de seguridad que vincula el archivo con la sesión. |

---

## 6. Flujo de Recuperación de Errores (Troubleshooting)

1. **La portada no cambia:** Verificar que `isArtworkSynchronized` sea true. Si es false, el pipeline de escritura falló o el `artworkKey` no se propagó correctamente.
2. **La imagen se ve pixelada:** La constante `MAX_ART_DIMENSION` (1024) es suficiente para widgets, pero si se ve mal, revisar el `inSampleSize` en `decodeBitmap`.
3. **Aparece la portada de la canción anterior:** Esto indica que el archivo `.key` no se borró al cambiar de pista. El sistema debe llamar a `saveTextToFile("", "album_art.key")` inmediatamente al detectar `trackChanged`.
