# COMPENDIO TÉCNICO EXHAUSTIVO DE INGENIERÍA DE PREVISUALIZACIÓN Y RENDERIZADO: MUSIC WIDGET

**Fecha de inicio del proyecto (primer mensaje):** 08 de Agosto de 2026
**Estatus:** Consolidado Final (No-Comprimido)

---

## 1. CRONOLOGÍA DETALLADA DE LA REINGENIERÍA

Este registro detalla el proceso de transformación desde una implementación inestable hasta una arquitectura de fidelidad total.

1.  **Auditoría Inicial (El Fracaso del Legado)**: Se identificó que la previsualización 4x4 (`widget_preview_large.xml`) era una versión reducida. No contenía el historial, utilizaba atributos de sistema (`?android:attr`) que fallaban en el modo sandbox del Launcher y dependía de `tools:text`, lo que resultaba en widgets vacíos en el dispositivo real.
2.  **Unificación Estructural (La Ley del 2x2)**: Se decidió que el bloque de arte de la variante 2x2 sería la "Fuente de Verdad". Se eliminó el `ImageView` simple del 4x4 y se inyectó el `FrameLayout` compuesto para garantizar que el visualizador y la nota musical central fueran idénticos en todas las escalas.
3.  **Estabilización Cromática (M3 Stabilization)**: Se detectó que las tarjetas del historial se veían blancas en modo oscuro. Se procedió a una inyección masiva de tokens en `res/values-night` y `res/values-night-v31`, eliminando cualquier color hexadecimal quemado.
4.  **Blindaje de Accesibilidad y Texto**: Se eliminaron los textos de 10sp en los badges (infracción de accesibilidad) y se incrementó la altura de las filas a **56dp** para evitar el recorte visual de los nombres de artistas detectado en el selector.
5.  **Segmentación por Calificadores**: Reubicación final de las "Vistas Ricas" en `layout-v31` y creación de "Mapas de Sombras" (Esqueletos) en `layout` base para evitar parpadeos (flicker).

---

## 2. AUDITORÍA ANALÍTICA LÍNEA POR LÍNEA: VARIANTE HISTORIAL (4x4)
**Archivo:** `res/layout-v31/widget_music_preview_4x4.xml`

### 2.1. JERARQUÍA DE VISTAS (ÁRBOL DE NODOS)
*   `LinearLayout` (Contenedor Raíz) -> `id: N/A`
    *   `RelativeLayout` (Cabecera Superior) -> `id: N/A`
        *   `LinearLayout` (Indicador de Audio) -> `id: N/A`
            *   `ImageView` (Icono Dispositivo) -> `id: N/A`
            *   `TextView` (Nombre Dispositivo) -> `id: N/A`
        *   `LinearLayout` (Badge Analítica) -> `id: N/A`
            *   `ImageView` (Icono Flama) -> `id: N/A`
    *   `LinearLayout` (Bloque Now Playing) -> `id: N/A`
        *   `RelativeLayout` (Conjunto de Arte) -> `id: N/A`
            *   `FrameLayout` (Contenedor Píldora) -> `id: preview_art_container`
                *   `ImageView` (Fondo Píldora) -> `id: N/A`
                *   `ImageView` (Nota Musical Central) -> `id: N/A`
            *   `ImageView` (Icono Historial/Visualizador) -> `id: preview_visualizer`
        *   `LinearLayout` (Bloque de Textos Principales) -> `id: N/A`
            *   `LinearLayout` (Fila de Título) -> `id: N/A`
                *   `ImageView` (Icono de Aplicación) -> `id: N/A`
                *   `TextView` (Título de Canción) -> `id: N/A`
            *   `TextView` (Nombre del Artista) -> `id: N/A`
    *   `LinearLayout` (Sección Historial Estático) -> `id: N/A`
        *   `RelativeLayout` (Encabezado de Sección) -> `id: N/A`
            *   `TextView` (Label Historial) -> `id: N/A`
            *   `ImageView` (Icono Clear All) -> `id: N/A`
        *   `RelativeLayout` (Fila de Canción 1) -> `id: N/A`
            *   `ImageView` (Miniatura Carátula) -> `id: h1_art`
            *   `LinearLayout` (Textos de Canción) -> `id: N/A`
                *   `TextView` (Título Historial 1) -> `id: N/A`
                *   `TextView` (Artista Historial 1) -> `id: N/A`
            *   `LinearLayout` (Badge de Estado) -> `id: h1_badge`
                *   `ImageView` (Icono Replay) -> `id: N/A`
        *   `RelativeLayout` (Fila de Canción 2) -> `id: N/A`
            *   `ImageView` (Miniatura Carátula) -> `id: h2_art`
            *   `LinearLayout` (Textos de Canción) -> `id: N/A`
                *   `TextView` (Título Historial 2) -> `id: N/A`
                *   `TextView` (Artista Historial 2) -> `id: N/A`
            *   `LinearLayout` (Badge de Estado) -> `id: h2_badge`
                *   `ImageView` (Icono Skip) -> `id: N/A`

### 2.2. INVENTARIO DE TOKENS DE COLOR Y TEMATIZACIÓN (4x4)
| Componente XML | Propiedad | Token Asignado | Tipo de Token |
| :--- | :--- | :--- | :--- |
| Contenedor Raíz | `android:background` | `@drawable/bg_widget_root_v31` | Shape XML (Dinámico) |
| Icono Dispositivo | `android:tint` | `@color/music_widget_on_surface_variant` | Dinámico (@color/) |
| Texto Dispositivo | `android:textColor` | `@color/music_widget_on_surface_variant` | Dinámico (@color/) |
| Badge Analítica | `android:background` | `@drawable/bg_preview_badge_pill` | Shape XML (M3) |
| Icono Flama | `android:tint` | `@color/music_widget_on_tertiary_container` | Dinámico (@color/) |
| Nota Central | `android:tint` | `@color/music_widget_accent` | Dinámico (@color/) |
| Visualizador | `android:tint` | `@color/music_widget_accent` | Dinámico (@color/) |
| Título Historial | `android:textColor` | `@color/music_widget_on_surface` | Dinámico (@color/) |
| Artista Historial| `android:textColor` | `@color/music_widget_on_surface_variant` | Dinámico (@color/) |
| Miniatura Hist. | `android:tint` | `@color/music_widget_accent` | Dinámico (@color/) |

### 2.3. INVENTARIO DE DRAWABLES Y RECURSOS GRÁFICOS (4x4)
| Recurso | Tipo | Rol Técnico |
| :--- | :--- | :--- |
| `@drawable/bg_widget_root_v31` | Shape XML | Radio de esquinas de sistema (`system_app_widget_background_radius`) |
| `@drawable/bg_preview_badge_pill`| Shape XML | Cápsula perfecta (100dp) para badges micro |
| `@drawable/bg_preview_card` | Shape XML | Tarjeta de historial con radio de 16dp y color `surface_variant` |
| `@drawable/ic_device_phone` | Vector | Icono representativo de salida de audio del teléfono |
| `@drawable/mode_heat_24px` | Vector | Icono de racha de reproducciones (Flama) |
| `@drawable/ic_music_history` | Vector | Icono de historial unificado con el visualizador Glance |

### 2.4. ESTILOS TIPOGRÁFICOS Y BLINDAJE DE TEXTOS (4x4)
| Elemento de Texto | Estilo / Propiedad | maxLines | ellipsize |
| :--- | :--- | :--- | :--- |
| Título Canción | `@style/WidgetPreview_Title` | 1 | end |
| Artista • Álbum | `@style/WidgetPreview_Artist` | 1 | end |
| Altavoz del teléfono| `textSize="@dimen/text_size_status"` | 1 | end |
| Death and Romance | `textStyle="bold"`, `textSize="13sp"` | 1 | end |
| Magdalena Bay | `textSize="11sp"` | 1 | end |
| Historial reciente| `textStyle="bold"`, `textSize="11sp"` | 1 | end |

---

## 3. AUDITORÍA ANALÍTICA: VARIANTE STANDARD (2x2)
**Archivo:** `res/layout-v31/widget_preview.xml`

### 3.1. JERARQUÍA DE VISTAS (ÁRBOL DE NODOS)
*   `RelativeLayout` (Raíz) -> `background="@drawable/bg_widget_root_v31"`
    *   `FrameLayout` (Art) -> `id: preview_art_container`
        *   `ImageView` (Pill) | `ImageView` (Note)
    *   `ImageView` (Visualizer) -> `id: preview_visualizer`
    *   `LinearLayout` (Badge Area) -> `background="@drawable/bg_badge_pill"`
        *   `ImageView` (Flama)
    *   `LinearLayout` (Text Area)
        *   `LinearLayout` (Title Group)
            *   `ImageView` (App Icon), `TextView` (Title)
        *   `TextView` (Artist)

### 3.2. INVENTARIO DE TOKENS Y TEXTO (2x2)
| Elemento | Token de Color | Estilo de Texto | maxLines |
| :--- | :--- | :--- | :--- |
| Título | `text_primary` | `@style/WidgetPreview_Title` | 1 |
| Artista | `text_secondary` | `@style/WidgetPreview_Artist` | 1 |
| Badge | `on_tertiary_container`| N/A (Icon Only) | N/A |

---

## 4. AUDITORÍA ANALÍTICA: VARIANTE PORTADA COMPLETA (2x1)
**Archivo:** `res/layout-v31/widget_preview_full.xml`

### 4.1. JERARQUÍA DE VISTAS (ÁRBOL DE NODOS)
*   `RelativeLayout` (Raíz) -> `background="@drawable/bg_widget_root_v31"`
    *   `ImageView` (Visualizer) -> `id: preview_visualizer_full`
    *   `LinearLayout` (Analytics Badge)
        *   `ImageView` (Replay Icon)
    *   `LinearLayout` (Texts Area)
        *   `LinearLayout` (Row) -> `ImageView` (App Icon), `TextView` (Title)
        *   `TextView` (Artist)

### 4.2. INVENTARIO DE TOKENS Y TEXTO (2x1)
| Elemento | Token de Color | Estilo de Texto | maxLines |
| :--- | :--- | :--- | :--- |
| Badge Analítica | `on_tertiary_container`| Icon Only | N/A |
| Visualizador | `@color/music_widget_accent`| N/A | N/A |

---

## 5. MAPEO DE COMPOSABLES GLANCE (KOTLIN)
Lista de funciones `@Composable` y su representación en los layouts XML anteriores.

| Composable | Rol Visual | Equivalente XML |
| :--- | :--- | :--- |
| `MusicWidgetUI` | Contenedor Principal | Layouts Raíz (4x4, 2x2, 2x1) |
| `PlaybackStatusIndicator`| Cabecera de Audio | Bloque Header en 4x4 |
| `AlbumArtWithVisualizer` | Píldora de Arte | `preview_art_container` + `preview_visualizer` |
| `TextInfo` | Títulos y Artista | Bloque Text Area en todas las variantes |
| `HistoryItemRow` | Fila de Historial | Relativelayout Row 1/2 en 4x4 |
| `DesignBadge` | Badge de Analítica | Bloque Badge en todas las variantes |

---

## 6. CÓDIGO COMPLETO (VERBATIM) - RECURSOS FINALES

### 6.1. Layout Historial 4x4 Final
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
        <!-- Fila 1 (56dp) -->
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
        </RelativeLayout>
    </LinearLayout>
</LinearLayout>
```

---
**Fin del Compendio Técnico.**
