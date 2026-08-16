# ESPECIFICACIÓN TÉCNICA MAESTRA Y AUDITORÍA DE INGENIERÍA: SISTEMA DE PREVISUALIZACIONES XML Y RENDERIZADO GLANCE (MUSIC WIDGET)

**Fecha de Inicio de Sesión:** 2026-08-08
**Estado:** Finalizado / Auditoría Completa
**Objetivo:** Garantizar paridad 1:1 entre Jetpack Glance y RemoteViews, estabilidad multi-API y fidelidad cromática Material You.

---

## 1. CRONOLOGÍA DE EVOLUCIÓN Y RESOLUCIÓN DE PROBLEMAS

La arquitectura actual es el resultado de una secuencia de optimizaciones técnicas destinadas a resolver fallos críticos en el selector de widgets:

1.  **Fase 1: Diagnóstico de Visibilidad e Inflado**: Se identificó que la previsualización 4x4 estaba incompleta (reducida a un reproductor simple) y utilizaba la etiqueta `<Space>`, prohibida en `RemoteViews`.
2.  **Fase 2: La Trampa de `tools:text`**: Se detectó que el Launcher ignoraba los textos de previsualización porque estaban definidos con el namespace `tools:`. Se migraron todos los atributos a `android:text` para asegurar visibilidad en el selector real.
3.  **Fase 3: Estabilización Cromática (Monet)**: Se abandonaron los atributos `?android:attr` debido a que el modo sandbox del Launcher los renderizaba como negro total. Se implementó una inyección de tokens dinámicos propios en carpetas calificadas.
4.  **Fase 4: Sincronía Milimétrica de Anclajes**: Se reescribieron los "Esqueletos de Carga" (Track A) para que sus dimensiones y márgenes (17dp, 110dp) coincidan exactamente con las "Vistas Ricas" (Track B), eliminando el parpadeo visual.
5.  **Fase 5: Corrección de Recortes (Alturas)**: Se identificó un recorte en los nombres de artista en 4x4. Se incrementó la altura de las filas de historial de 48dp a 56dp para soportar escalado de fuentes sin perder integridad visual.

---

## 2. ARQUITECTURA DE FIDELIDAD CROMÁTICA (M3 TOKENS)

Se utiliza una **Fuente de Verdad Unificada** entre Glance (Kotlin) y RemoteViews (XML):

| Token Conceptual | Uso en el Sistema | Valor Claro (v31) | Valor Oscuro (Night-v31) |
| :--- | :--- | :--- | :--- |
| `widgetBackground` | Contenedor Raíz | `@android:color/system_neutral2_50` | `@android:color/system_neutral2_800` |
| `onSurface` | Títulos y Cabeceras | `@android:color/system_neutral1_900` | `@android:color/system_neutral1_50` |
| `onSurfaceVariant` | Artista / Estados | `@android:color/system_neutral2_700` | `@android:color/system_neutral2_200` |
| `surfaceVariant` | Tarjetas Historial | `@android:color/system_neutral2_200` | `@android:color/system_neutral2_700` |
| `tertiaryContainer`| Badges Analítica | `@android:color/system_accent3_200` | `@android:color/system_accent3_700` |
| `primaryContainer` | Portada Historial | `@android:color/system_accent1_100` | `@android:color/system_accent1_700` |

---

## 3. AUDITORÍA ANALÍTICA DE PREVISUALIZACIONES (VISTAS RICAS V31)

### A. Variante Historial (4x4): `widget_music_preview_4x4.xml`

#### JERARQUÍA DE VISTAS (ÁRBOL DE NODOS)
- `LinearLayout` (Raíz, `@drawable/bg_widget_root_v31`)
  - `RelativeLayout` (Cabecera)
    - `LinearLayout` (Status) -> `ImageView`, `TextView`
    - `LinearLayout` (Badge) -> `ImageView` (Icon Only)
  - `LinearLayout` (Now Playing)
    - `RelativeLayout` (Art Container) -> `FrameLayout` (`ImageView` x2), `ImageView` (Visualizer)
    - `LinearLayout` (Text Info) -> `LinearLayout` (`ImageView`, `TextView`), `TextView`
  - `LinearLayout` (Historial)
    - `RelativeLayout` (Header) -> `TextView`, `ImageView`
    - `RelativeLayout` (Fila 1, `56dp`) -> `ImageView`, `LinearLayout` (`TextView`, `TextView`), `LinearLayout` (Badge Icon)
    - `RelativeLayout` (Fila 2, `56dp`) -> `ImageView`, `LinearLayout` (`TextView`, `TextView`), `LinearLayout` (Badge Icon)

#### INVENTARIO DE TOKENS Y ESTILOS
| Elemento | Propiedad | Token / Estilo | Estado Blindaje |
| :--- | :--- | :--- | :--- |
| Fondo Raíz | `background` | `@drawable/bg_widget_root_v31` | Radio Sistema OK |
| Título Central | `text` | `@string/widget_empty_title` | `style="@style/WidgetPreview_Title"` |
| Artista Central | `text` | `@string/widget_empty_subtitle`| `style="@style/WidgetPreview_Artist"` |
| Título Historial | `textColor` | `@color/music_widget_on_surface` | `maxLines="1"`, `ellipsize="end"` |
| Artista Historial| `textColor` | `@color/music_widget_on_surface_variant`| `maxLines="1"`, `ellipsize="end"` |

#### AUDITORÍA DE SEGURIDAD Y ACCESIBILIDAD
- ✅ **RemoteViews Safe**: Cero etiquetas `<Space>` o `<View>`.
- ✅ **Accesibilidad**: Iconos funcionales tienen descripción real (`@string/status_listening`).
- ✅ **Blindaje M3**: Sin atributos `?android:attr`.

---

### B. Variante Standard (2x2): `widget_preview.xml`

#### JERARQUÍA DE VISTAS (ÁRBOL DE NODOS)
- `RelativeLayout` (Raíz, `@drawable/bg_widget_root_v31`)
  - `FrameLayout` (Art) -> `ImageView` (Pill), `ImageView` (Note)
  - `ImageView` (Visualizer)
  - `LinearLayout` (Badge) -> `ImageView` (Icon Only)
  - `LinearLayout` (Texts) -> `LinearLayout` (`ImageView`, `TextView`), `TextView`

#### INVENTARIO DE TOKENS Y ESTILOS
| Elemento | Propiedad | Token / Estilo | Estado Blindaje |
| :--- | :--- | :--- | :--- |
| Píldora Arte | `src` | `@drawable/ic_preview_pill` | `110dp x 110dp` |
| Visualizador | `tint` | `@color/music_widget_accent` | `@string/content_desc_visualizer` |
| Badge | `background` | `@drawable/bg_badge_pill` | Icono Flama Centrado |

---

### C. Variante Portada Completa (2x1): `widget_preview_full.xml`

#### JERARQUÍA DE VISTAS (ÁRBOL DE NODOS)
- `RelativeLayout` (Raíz, `@drawable/bg_widget_root_v31`)
  - `ImageView` (Visualizer Full)
  - `LinearLayout` (Badge Top Right) -> `ImageView` (Icon Only)
  - `LinearLayout` (Texts Bottom) -> `LinearLayout` (`ImageView`, `TextView`), `TextView`

#### INVENTARIO DE TOKENS Y ESTILOS
| Elemento | Propiedad | Token / Estilo | Estado Blindaje |
| :--- | :--- | :--- | :--- |
| Visualizador | `id` | `preview_visualizer_full` | `alignParentBottom/End` |
| Texto Título | `style` | `@style/WidgetPreview_Title` | `color/music_widget_text_primary` |

---

## 4. CÓDIGO VERBATIM: LAYOUTS RICAS (RES/LAYOUT-V31/)

### widget_music_preview_4x4.xml (Estado Final 56dp / Altavoz)
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="@dimen/widget_padding"
    android:background="@drawable/bg_widget_root_v31"
    android:theme="@style/Theme.MusicWidget"
    android:clipToOutline="true">

    <RelativeLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content">
        <LinearLayout
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_alignParentStart="true"
            android:layout_centerVertical="true"
            android:orientation="horizontal"
            android:gravity="center_vertical">
            <ImageView
                android:layout_width="@dimen/icon_size_small"
                android:layout_height="@dimen/icon_size_small"
                android:src="@drawable/ic_device_phone"
                android:tint="@color/music_widget_on_surface_variant"
                android:contentDescription="@string/status_listening" />
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginStart="4dp"
                android:text="Altavoz del teléfono"
                android:textSize="@dimen/text_size_status"
                android:textColor="@color/music_widget_on_surface_variant"
                android:maxLines="1"
                android:ellipsize="end" />
        </LinearLayout>
        <LinearLayout
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_alignParentEnd="true"
            android:layout_centerVertical="true"
            android:background="@drawable/bg_preview_badge_pill"
            android:paddingHorizontal="8dp"
            android:paddingVertical="4dp"
            android:orientation="horizontal"
            android:gravity="center">
            <ImageView
                android:layout_width="12dp"
                android:layout_height="12dp"
                android:src="@drawable/mode_heat_24px"
                android:tint="@color/music_widget_on_tertiary_container"
                android:contentDescription="@null" />
        </LinearLayout>
    </RelativeLayout>

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="32dp"
        android:baselineAligned="false"
        android:orientation="horizontal"
        android:gravity="center_vertical">
        <RelativeLayout
            android:layout_width="wrap_content"
            android:layout_height="wrap_content">
            <FrameLayout
                android:id="@+id/preview_art_container"
                android:layout_width="@dimen/album_art_size_classic"
                android:layout_height="@dimen/album_art_size_classic">
                <ImageView
                    android:layout_width="match_parent"
                    android:layout_height="match_parent"
                    android:src="@drawable/ic_preview_pill"
                    android:contentDescription="@null" />
                <ImageView
                    android:layout_width="@dimen/skeleton_art_inner_icon_size"
                    android:layout_height="@dimen/skeleton_art_inner_icon_size"
                    android:layout_gravity="center"
                    android:src="@drawable/ic_music_note"
                    android:tint="@color/music_widget_accent"
                    android:alpha="0.6"
                    android:contentDescription="@null" />
            </FrameLayout>
            <ImageView
                android:id="@+id/preview_visualizer"
                android:layout_width="@dimen/visualizer_size"
                android:layout_height="@dimen/visualizer_size"
                android:layout_alignBottom="@id/preview_art_container"
                android:layout_alignEnd="@id/preview_art_container"
                android:layout_marginBottom="@dimen/visualizer_offset_bottom"
                android:layout_marginEnd="@dimen/visualizer_offset_end"
                android:src="@drawable/ic_music_history"
                android:tint="@color/music_widget_accent"
                android:contentDescription="@string/content_desc_visualizer" />
        </RelativeLayout>
        <LinearLayout
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:layout_marginStart="@dimen/widget_content_margin_start"
            android:orientation="vertical">
            <LinearLayout
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:gravity="center_vertical">
                <ImageView
                    android:layout_width="@dimen/icon_size_medium"
                    android:layout_height="@dimen/icon_size_medium"
                    android:layout_marginEnd="6dp"
                    android:src="@drawable/ic_music_note"
                    android:tint="@color/music_widget_accent"
                    android:contentDescription="@string/content_desc_app_icon" />
                <TextView
                    style="@style/WidgetPreview_Title"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/widget_empty_title" />
            </LinearLayout>
            <TextView
                style="@style/WidgetPreview_Artist"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/widget_empty_subtitle" />
        </LinearLayout>
    </LinearLayout>

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:orientation="vertical">
        <RelativeLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginBottom="8dp">
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_alignParentStart="true"
                android:text="@string/history_header"
                android:textSize="@dimen/text_size_history_header"
                android:textColor="@color/music_widget_accent"
                android:textStyle="bold" />
            <ImageView
                android:layout_width="@dimen/history_header_icon_size"
                android:layout_height="@dimen/history_header_icon_size"
                android:layout_alignParentEnd="true"
                android:src="@drawable/clear_all_24px"
                android:tint="@color/music_widget_on_surface_variant"
                android:contentDescription="@string/content_desc_clear_history" />
        </RelativeLayout>
        <RelativeLayout
            android:layout_width="match_parent"
            android:layout_height="56dp"
            android:layout_marginBottom="4dp"
            android:background="@drawable/bg_preview_card"
            android:padding="8dp">
            <ImageView
                android:id="@+id/h1_art"
                android:layout_width="@dimen/history_item_art_width"
                android:layout_height="@dimen/history_item_art_height"
                android:layout_centerVertical="true"
                android:background="@drawable/bg_badge_note"
                android:src="@drawable/ic_music_note"
                android:padding="6dp"
                android:tint="@color/music_widget_accent"
                android:contentDescription="@null" />
            <LinearLayout
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_toEndOf="@id/h1_art"
                android:layout_toStartOf="@+id/h1_badge"
                android:layout_marginStart="12dp"
                android:layout_centerVertical="true"
                android:orientation="vertical">
                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="Death and Romance"
                    android:textSize="@dimen/text_size_history_item_title"
                    android:textStyle="bold"
                    android:textColor="@color/music_widget_on_surface"
                    android:maxLines="1"
                    android:ellipsize="end" />
                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="Magdalena Bay"
                    android:textSize="@dimen/text_size_history_item_artist"
                    android:textColor="@color/music_widget_on_surface_variant"
                    android:maxLines="1"
                    android:ellipsize="end" />
            </LinearLayout>
            <LinearLayout
                android:id="@+id/h1_badge"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_alignParentEnd="true"
                android:layout_centerVertical="true"
                android:background="@drawable/bg_preview_badge_pill"
                android:paddingHorizontal="8dp"
                android:paddingVertical="4dp"
                android:orientation="horizontal"
                android:gravity="center">
                <ImageView
                    android:layout_width="12dp"
                    android:layout_height="12dp"
                    android:src="@drawable/replay_24px"
                    android:tint="@color/music_widget_on_tertiary_container"
                    android:contentDescription="@null" />
            </LinearLayout>
        </RelativeLayout>
    </LinearLayout>
</LinearLayout>
```

---

## 5. REPOSITORIO DE RECURSOS GEOMÉTRICOS Y ESTILOS

### [NEW] bg_widget_root_v31.xml
Radio de sistema oficial para Android 12+.
```xml
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <solid android:color="@color/music_widget_background" />
    <corners android:radius="@android:dimen/system_app_widget_background_radius" />
</shape>
```

### [MODIFY] styles.xml (Typography Sync)
```xml
<style name="WidgetPreview_Title">
    <item name="android:textSize">@dimen/text_size_title</item>
    <item name="android:textColor">@color/music_widget_text_primary</item>
    <item name="android:textStyle">bold</item>
    <item name="android:maxLines">1</item>
    <item name="android:ellipsize">end</item>
    <item name="android:singleLine">true</item>
</style>
```

---

## 6. LÓGICA DE SINCRONÍA GLANCE (KOTLIN)

Fragmento crítico de `MusicWidget.kt` que inyecta la identidad para evitar fallos de reflexión:

```kotlin
class MusicWidgetFullReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = MusicWidget(WidgetAppearance.FULL)
}
class MusicWidgetPillReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = MusicWidget(WidgetAppearance.PILL_STANDARD)
}
class MusicWidgetControlReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = MusicWidget(WidgetAppearance.PILL_CONTROL)
}

@Composable
internal fun MusicWidgetUI(appearance: WidgetAppearance, ...) {
    // Determinismo de diseño forzado para el selector
    val isWide = if (isPreview) forcedAppearance == WidgetAppearance.PILL_CONTROL else size.width.value >= 220f
    // ...
}
```

---

## 7. REGISTRO DE DECISIONES Y OPCIONES DESCARTADAS

*   **DESCARTADO: `SizeMode.Responsive`**: Provocaba márgenes fantasmas imposibles de predecir. Se eligió `SizeMode.Exact` para control total del renderizado.
*   **DESCARTADO: `tools:text`**: Descartado por ser invisible en el Launcher real. Se eligió `android:text` con valores de previsualización realistas.
*   **DESCARTADO: `?android:attr/colorBackground`**: Descartado por crasheos en emuladores y fondos negros. Se eligió la paleta `@color/music_widget_background`.
*   **DECISIÓN: Altura 56dp**: Se incrementó la altura fija de las filas del historial de 48dp a 56dp para blindar el diseño contra el escalado de fuentes del sistema.

---
**FIN DEL DOCUMENTO MAESTRO.**
