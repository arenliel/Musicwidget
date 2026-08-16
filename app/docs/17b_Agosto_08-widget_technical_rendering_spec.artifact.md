# DOCUMENTO TÉCNICO DE COMPOSABLES Y RENDERIZADO EXHAUSTIVO

Este documento constituye el mapa técnico definitivo del **Music Widget**, cruzando las especificaciones visuales conceptuales con la implementación real en Kotlin (Jetpack Glance).

---

## 1. DICCIONARIO Y NOMBRES DE COMPOSABLES
Lista exhaustiva de funciones `@Composable` definidas en el proyecto, su ubicación y firmas de parámetros.

| Composable | Archivo | Parámetros (Firma Exacta) |
| :--- | :--- | :--- |
| `MusicWidgetUI` | `MusicWidget.kt` | `info: MusicInfo`, `albumArtBitmap: Bitmap?`, `appIconBitmap: Bitmap?`, `isArtworkSynchronized: Boolean`, `isIconSynchronized: Boolean`, `forcedAppearance: WidgetAppearance`, `isPreview: Boolean`, `explicitPillSize: Dp?` |
| `Layout4x4` | `MusicWidget.kt` | `context: Context`, `info: MusicInfo`, `albumArtBitmap: Bitmap?`, `appIconBitmap: Bitmap?`, `isArtworkSynchronized: Boolean`, `isIconSynchronized: Boolean`, `pillSize: Dp`, `maxLines: Int = 1` |
| `Layout2x1` | `MusicWidget.kt` | `context: Context`, `info: MusicInfo`, `albumArtBitmap: Bitmap?`, `appIconBitmap: Bitmap?`, `isArtworkSynchronized: Boolean`, `isIconSynchronized: Boolean`, `maxArtistLines: Int = 1` |
| `TextInfo` | `MusicWidget.kt` | `context: Context`, `info: MusicInfo`, `appIconBitmap: Bitmap?`, `showRelativeTime: Boolean`, `isIconSynchronized: Boolean`, `maxArtistLines: Int = 1` |
| `AlbumArtWithVisualizer`| `MusicWidget.kt` | `context: Context`, `info: MusicInfo`, `albumArtBitmap: Bitmap?`, `isArtworkSynchronized: Boolean`, `pillSize: Dp`, `showVisualizer: Boolean = true` |
| `RepeatAnalyticsBadge` | `MusicWidget.kt` | `info: MusicInfo` |
| `DesignBadge` | `MusicWidget.kt` | `iconRes: Int`, `label: String`, `isTonal: Boolean = true` |
| `HistoryList` | `MusicWidget.kt` | `context: Context`, `history: List<HistoryItem>` |
| `HistoryItemRow` | `MusicWidget.kt` | `context: Context`, `item: HistoryItem` |
| `PlaybackStatusIndicator`| `MusicWidget.kt` | `iconRes: Int`, `device: String`, `status: String`, `showDeviceName: Boolean = false` |
| `VisualizerSelector` | `MusicWidget.kt` | `context: Context`, `info: MusicInfo`, `size: Dp` |
| `PermissionsView` | `MusicWidget.kt` | `context: Context`, `info: MusicInfo` |

---

## 2. ELEMENTOS GRÁFICOS Y GLANCE COMPOSABLES
Mapeo de componentes visuales con los elementos de Glance y modificadores aplicados.

### [MusicWidgetUI](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicWidget.kt#L236) (Contenedor Raíz)
- **Glance Composable:** `Box`.
- **Modificadores:**
  - `.fillMaxSize()`: Ocupa todo el espacio disponible del widget.
  - `.cornerRadius(widgetRadius)`: Aplica el radio de curvatura dinámico (`android.R.dimen.system_app_widget_background_radius`).
  - `.background(GlanceTheme.colors.widgetBackground)`: Aplica el color de fondo dinámico Material You.

### [AlbumArtWithVisualizer](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicWidget.kt#L529) (Píldora de Arte)
- **Glance Composables:** `Box` (contenedor), `Image` (carátula/placeholder).
- **Modificadores:**
  - `.size(pillSize)`: Tamaño variable entre 80dp y 110dp según la colisión.
  - `.clickable(actionStartActivity(...))`: Abre la actividad de detalle de carátula.
  - `.cornerRadius(8.dp)` (dentro de placeholders).
  - `.padding(bottom = safetyMargin, end = safetyMargin)`: Posicionamiento del visualizador.

### [HistoryItemRow](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicWidget.kt#L358) (Item de Lista)
- **Glance Composables:** `Box` (margen), `Row` (contenedor principal), `Column` (textos).
- **Modificadores:**
  - `.fillMaxWidth()`: Estiramiento horizontal.
  - `.background(GlanceTheme.colors.surfaceVariant)`: Color de fondo de la tarjeta.
  - `.cornerRadius(16.dp)`: Bordes redondeados de la tarjeta.
  - `.clickable(actionStartActivity(...))`: Dispara búsqueda en reproductores.
  - `.padding(8.dp)`: Relleno interno.

### [DesignBadge](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicWidget.kt#L415) (Badge Micro)
- **Glance Composables:** `Row`, `Image`, `Text`.
- **Modificadores:**
  - `.background(if (isTonal) GlanceTheme.colors.secondaryContainer else ...)`: Fondo tonal M3.
  - `.cornerRadius(100.dp)`: Forma circular/elíptica perfecta.
  - `.padding(horizontal = 6.dp, vertical = 2.dp)`: Micro-padding.

---

## 3. CONDICIONES DE RENDERIZADO (LÓGICA EXACTA)

### Sensor de Colisión Vertical
Determina si se renderiza el modo **Full-Bleed** (`Layout2x1`) o el modo **Stacked/Wide** (`Layout4x4`).
```kotlin
val estimatedTextSpace = (16f + 14f + 12f) * fontScale
val minHeightForPill = (widgetPaddingVal * 2) + estimatedTextSpace + 80f
val hasCollision = widgetSize.height.value < minHeightForPill
val isActuallyFullBleed = forcedAppearance == WidgetAppearance.FULL || hasCollision
```

### Lógica del Badge de Racha/Streak
La función `RepeatAnalyticsBadge` evalúa los estados de `MusicInfo` para decidir la visibilidad y contenido del badge.
```kotlin
val badge = when {
    info.streakDays >= 3 -> Pair(R.drawable.replay_24px, "${info.streakDays}d")
    info.playsToday >= 3 -> Pair(R.drawable.mode_heat_24px, "${info.playsToday}x")
    info.skipStreak >= 1 -> Pair(R.drawable.skip_next_24px, if (info.skipStreak >= 3) "${info.skipStreak}x" else "")
    else -> null
} ?: return // Si es null, no se dibuja nada
```
> [!IMPORTANT]
> El badge de **Racha (d)** tiene prioridad absoluta sobre el de **Repetición (x)**. La racha se considera vigente si `streakDays >= 3`.

### Alternancia de Letras Sincronizadas
En `TextInfo`, las letras se muestran solo bajo validación de identidad de pista.
```kotlin
val artistText = when {
    // ...
    info.isSessionActive && info.showLyrics && info.currentLyric.isNotBlank() && info.trackKey == info.lyricsTrackKey -> "“${info.currentLyric}”"
    else -> info.artist
}
```

---

## 4. TOKENS DE COLOR Y ESTILOS A NIVEL DE CÓDIGO

Relación de componentes con los tokens de `GlanceTheme.colors` y estilos tipográficos.

| Elemento Visual | Token Material You | Estilo de Texto / Propiedad |
| :--- | :--- | :--- |
| **Fondo General** | `GlanceTheme.colors.widgetBackground` | Color de base reactivo al wallpaper. |
| **Título Canción** | `GlanceTheme.colors.onSurface` | `fontWeight = FontWeight.Bold`, `fontSize = titleSize`. |
| **Artista / Estatus**| `GlanceTheme.colors.onSurfaceVariant` | `fontWeight = FontWeight.Normal`, `fontSize = artistSize`. |
| **Letras (Highlight)**| `GlanceTheme.colors.primary` | `fontStyle = FontStyle.Italic`, `fontWeight = FontWeight.Medium`. |
| **Badge Tonal** | `GlanceTheme.colors.secondaryContainer`| Fondo del badge dinámico. |
| **Texto Badge** | `GlanceTheme.colors.onSecondaryContainer`| Contraste alto sobre el badge. |
| **Píldora Dispositivo**| `GlanceTheme.colors.primaryContainer` | (Conceptual) Implementado mediante tinte en iconos. |
| **Icono App** | `GlanceTheme.colors.primary` | Filtro aplicado si el icono está sincronizado. |

### Radios y Espaciado
- **Contenedor Raíz:** `android.R.dimen.system_app_widget_background_radius`.
- **Píldora de Arte:** 80dp a 110dp (escalado compresible).
- **Separación Historial:** `3.dp` (padding inferior en `Box` de fila).
- **Padding General:** `dimen(R.dimen.widget_padding)` (17dp estándar).

---
**Documento persistido en el sistema de artefactos.**
render_diffs(file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicWidget.kt)
render_diffs(file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt)
