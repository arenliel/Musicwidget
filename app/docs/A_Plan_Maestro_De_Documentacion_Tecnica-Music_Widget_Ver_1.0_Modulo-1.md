
# PLAN MAESTRO DE DOCUMENTACIÓN TÉCNICA: MUSIC WIDGET (VERSION 1.0)

## TAXONOMÍA Y CATEGORIZACIÓN LOGICA

Para cubrir el 100% del espectro técnico sin redundancias, se propone la siguiente estructura modular de 6 categorías:

1.  **Módulo 1: Sistema UI y Motor de Composables (Glance):** Especificación de la interfaz declarativa, lógica de renderizado exacto, gestión de colisiones de tamaño y tokens de diseño Material You.
2.  **Módulo 2: Núcleo de Arquitectura y Flujo de Datos (UDF):** Despacho atómico de actualizaciones (uiUpdateFlow), control de ráfagas (Debounce), concurrencia y seguridad de hilos (Mutex).
3.  **Módulo 3: Persistencia, Identidad y Analítica de Escucha:** Gestión de DataStore, sistema de llaves digitales (trackKey), motor de rachas (Streaks) y lógica de "Canción Bendecida".
4.  **Módulo 4: Intercepción Multimedia y Resolución de Assets:** NotificationListenerService, integración con MediaSession, traductor de CDN (Spotify) y sistema de rescate de iconos.
5.  **Módulo 5: Compatibilidad Multi-API y Previsualizaciones XML:** Arquitectura de esqueletos (Skeletons), layouts v31 vs Legacy, y paridad visual RemoteViews vs Glance.
6.  **Módulo 6: Registro de Errores, Optimización y Mantenimiento:** Bitácora de fallos críticos (NaN, Skia, Memory), optimización energética (Battery Zero) y guía de despliegue.

---

## MÓDULO 1 - SISTEMA DE INTERFAZ DE USUARIO (UI) Y MOTOR DE COMPOSABLES GLANCE

Este módulo define la implementación física y lógica de la interfaz del widget utilizando Jetpack Glance. Es la especificación inalterable para el archivo `MusicWidget.kt`.

### 1. ESPECIFICACIONES TÉCNICAS DE RENDERIZADO
| Parámetro | Valor / Estrategia | Justificación Técnica |
| :--- | :--- | :--- |
| **Modo de Tamaño** | `SizeMode.Exact` | Elimina distorsiones visuales y el "Efecto Chicle" de los buckets de Responsive. |
| **Radio de Esquina** | `android.R.dimen.system_app_widget_background_radius` | Garantiza integración nativa con el sistema (Android 12+). |
| **Padding Global** | `17.dp` | Estándar de ergonomía visual para widgets de sistema. |
| **Resolución Imagen** | 600px (Máx) / 120px (Historial) | Evita `TransactionTooLargeException` en RemoteViews. |

### 2. DICCIONARIO DE COMPOSABLES (ESTRUCTURA DE CÓDIGO)

#### 2.1. Punto de Entrada: MusicWidgetUI
Función maestra que gestiona el estado de carga y la seguridad contra fallos de inicialización (Anti-NaN).

```kotlin
@Composable
fun MusicWidgetUI(
    info: MusicInfo,
    albumArtBitmap: Bitmap?,
    appIconBitmap: Bitmap?,
    isArtworkSynchronized: Boolean,
    isIconSynchronized: Boolean,
    forcedAppearance: WidgetAppearance,
    isPreview: Boolean,
    explicitPillSize: Dp?
) {
    val size = LocalSize.current

    // Composición defensiva contra LocalSize Unspecified (Bug del Recuadro Negro)
    if (size.width.value == 0f || size.height.value == 0f || size.width.value.isNaN() || size.height.value.isNaN()) {
        Box(modifier = GlanceModifier.fillMaxSize()) {}
        return
    }

    val context = GlanceTheme.context
    val appearance = forcedAppearance

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .background(GlanceTheme.colors.widgetBackground),
        contentAlignment = Alignment.Center
    ) {
        when (appearance) {
            WidgetAppearance.SMALL -> Layout2x1(context, info, albumArtBitmap, appIconBitmap, isArtworkSynchronized, isIconSynchronized)
            WidgetAppearance.STANDARD -> Layout4x4(context, info, albumArtBitmap, appIconBitmap, isArtworkSynchronized, isIconSynchronized, pillSize = 110.dp)
            WidgetAppearance.LARGE -> Layout4x4(context, info, albumArtBitmap, appIconBitmap, isArtworkSynchronized, isIconSynchronized, pillSize = 110.dp)
        }
    }
}
````

#### 2.2. Motor de Textos: TextInfo

El "cerebro visual" que decide la prioridad de información según el contexto de reproducción.

```
@Composable
fun TextInfo(
    context: Context,
    info: MusicInfo,
    appIconBitmap: Bitmap?,
    showRelativeTime: Boolean,
    isIconSynchronized: Boolean,
    maxArtistLines: Int = 1
) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Renderizado de Icono de Aplicación con Filtro de Sincronía
            appIconBitmap?.let {
                Image(
                    provider = ImageProvider(it),
                    contentDescription = null,
                    modifier = GlanceModifier.size(16.dp),
                    colorFilter = if (isIconSynchronized) ColorFilter.tint(GlanceTheme.colors.primary) else null
                )
            }
            Spacer(modifier = GlanceModifier.width(8.dp))
            Text(
                text = info.title,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                ),
                maxLines = 1
            )
        }

        // Lógica de Letras Sincronizadas (Lyrics) o Estado de Tiempo
        val artistText = if (info.lyrics.isNotEmpty() && info.isSessionActive) {
            info.lyrics
        } else if (showRelativeTime && !info.isSessionActive) {
            "\\({info.artist} • Hace \\){info.relativeTime}"
        } else {
            info.artist
        }

        Text(
            text = artistText,
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 12.sp,
                fontStyle = if (info.lyrics.isNotEmpty()) FontStyle.Italic else FontStyle.Normal
            ),
            maxLines = maxArtistLines
        )
    }
}
```

### 3. LÓGICA DE ESCALADO Y COLISIÓN (COMPRESSIBLE PILL)

Para garantizar la legibilidad en configuraciones de accesibilidad (fuentes al 200%), se utiliza un cálculo de "Píldora Elástica":

1. **Rango de Escalado:** 110dp (Máximo) a 80dp (Mínimo de seguridad).
2. **Algoritmo:** `Altura Disponible - (Espacio de Texto + Paddings) = Tamaño Píldora`.
3. **Sensor de Supervivencia:** Si el tamaño resultante es `< 80dp`, el sistema conmuta automáticamente al `Layout2x1` (Full-Bleed), eliminando la píldora para priorizar el texto.

### 4. MAPA DE TOKENS MATERIAL YOU (M3 EXPRESSIVE)

Estos tokens deben ser respetados tanto en Glance como en las previsualizaciones XML para asegurar paridad cromática.

|Token Conceptual|Elemento Asociado|Valor GlanceTheme|
|:--|:--|:--|
|`widgetBackground`|Contenedor Raíz|`GlanceTheme.colors.widgetBackground`|
|`onSurface`|Título de Canción|`GlanceTheme.colors.onSurface`|
|`onSurfaceVariant`|Artista y Metadatos|`GlanceTheme.colors.onSurfaceVariant`|
|`primary`|Letras e Icono Sincronizado|`GlanceTheme.colors.primary`|
|`tertiaryContainer`|Badges de Analítica (Streaks)|`GlanceTheme.colors.tertiaryContainer`|
|`surfaceVariant`|Tarjetas del Historial|`GlanceTheme.colors.surfaceVariant`|

### 5. LISTA NEGRA DE PRÁCTICAS (ANTI-PATRONES)

- **PROHIBIDO:** Usar `SizeMode.Responsive`. Causa saltos visuales bruscos y márgenes fantasma en la rejilla del Launcher.
- **PROHIBIDO:** Usar `android:tint` en archivos XML de vectores. Provoca fallos de vinculación de recursos; el tintado debe ser programático mediante `ColorFilter.tint()`.
- **PROHIBIDO:** Usar `getReceiverName` vía reflexión para identificar el widget. Falla en el selector de widgets (Picker). Se debe usar la inyección del Enum `WidgetAppearance` en el constructor.
- **PROHIBIDO:** Retornar `null` o hacer un `return` seco en `MusicWidgetUI` si el tamaño es indeterminado. Esto deja el widget "atascado" en el Skeleton de carga; siempre debe emitirse una composición mínima (Box vacío).
- **PROHIBIDO:** Usar la etiqueta `<View />` o `<Space />` en esqueletos XML de Glance. RemoteViews no las soporta y causará que el widget no se pueda añadir. Usar `ImageView` o `layout_margin`.

---

**FIN DEL MÓDULO 1**