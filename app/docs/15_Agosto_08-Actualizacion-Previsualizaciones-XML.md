# 08 DE AGOSTO DE 2026: BITÁCORA TÉCNICA DE REINGENIERÍA DE PREVISUALIZACIONES XML Y ESTABILIZACIÓN M3 PARA MUSIC WIDGET

## 1. INTRODUCCIÓN Y OBJETIVO DEL PROYECTO
Este documento registra el proceso exhaustivo de reconstrucción de las previsualizaciones estáticas (`android:previewLayout`) y los esqueletos de carga (`android:initialLayout`) del **Music Widget**. La meta fue alcanzar una paridad visual del 100% con los componentes declarativos de **Jetpack Glance** (Kotlin) y garantizar la estabilidad técnica en el motor de **RemoteViews**, eliminando errores de inflado, fallos de visibilidad y discrepancias cromáticas en el selector de widgets de Android.

---

## 2. SECUENCIA CRONOLÓGICA DE AUDITORÍA Y EJECUCIÓN

### 2.1. Fase I: Detección de Inconsistencias y Anti-patrones
Se identificó que la arquitectura previa fallaba por cuatro motivos críticos:
1.  **Reduccionismo del Layout 4x4**: El archivo `widget_preview_large.xml` trataba el widget como un reproductor simple, omitiendo el historial reciente y la cabecera de dispositivo que el Composable `Layout4x4` sí renderizaba.
2.  **Inestabilidad de Atributos**: El uso de `?android:attr` para colores y tintes provocaba fondos negros en el modo sandbox del Launcher.
3.  **Incompatibilidad de Etiquetas**: El uso de `<Space />` y `<View />` causaba errores de compilación silenciosos en `RemoteViews`.
4.  **Error de Visibilidad (`tools:text`)**: Las previsualizaciones aparecían vacías en el teléfono real porque el SO ignora el namespace `tools:`.

### 2.2. Fase II: Establecimiento de la "Fuente de Verdad"
Se determinó que la variante **Standard (2x2)** (`widget_preview.xml`) era la referencia visual correcta. Se decidió que cualquier cambio en el bloque de "Now Playing" o en la "Píldora de Arte" debía ser replicado exactamente desde este archivo para garantizar la cohesión de marca.

### 2.3. Fase III: Estabilización Cromática y Modo Oscuro
Se detectó que en el selector real, las tarjetas del historial se veían blancas sobre fondos oscuros. Se descubrió que la carpeta `values-night` carecía de los tokens M3 dinámicos, provocando un fallback a los colores claros por defecto.

### 2.4. Fase IV: Refinamiento de Accesibilidad y Ergonomía Visual
1.  **Cura de Badges**: Se eliminaron los números (ej. "3x") de las previsualizaciones para que el badge sea solo un icono limpio en su cápsula, eliminando advertencias de "Texto Micro" (10sp).
2.  **Parche de Recorte**: Se detectó que el nombre del artista en el historial se cortaba a la mitad. Se incrementó la altura de fila de `48dp` a **`56dp`**.
3.  **Anclaje de Texto**: Se fijó el anclaje superior a **`32dp`** para evitar saltos por el factor de escala de fuente.

---

## 3. AUDITORÍA ANALÍTICA INDIVIDUALIZADA (INVENTARIO TÉCNICO)

### 3.1. VARIANTE HISTORIAL (4x4): `widget_music_preview_4x4.xml`

#### 1. JERARQUÍA DE VISTAS (ÁRBOL DE NODOS)
*   **`LinearLayout` (Raíz)** -> `android:id: N/A`
    *   **`RelativeLayout` (Cabecera)** -> `android:id: N/A`
        *   **`LinearLayout` (Área de Audio)** -> `alignParentStart="true"`
            *   `ImageView` (Icono Dispositivo) -> `id: N/A`
            *   `TextView` (Dispositivo) -> `id: N/A`
        *   **`LinearLayout` (Analytics Badge)** -> `alignParentEnd="true"`
            *   `ImageView` (Flama) -> `id: N/A`
    *   **`LinearLayout` (Contenedor Now Playing)** -> `marginTop="32dp"`
        *   **`RelativeLayout` (Art Stack)** -> `android:id: N/A`
            *   **`FrameLayout` (Art Container)** -> `android:id="@+id/preview_art_container"`
                *   `ImageView` (Pill Placeholder) -> `id: N/A`
                *   `ImageView` (Musical Note Icon) -> `id: N/A`
            *   **`ImageView` (Visualizer/History Icon)** -> `android:id="@+id/preview_visualizer"`
        *   **`LinearLayout` (Block Text Info)** -> `layout_weight="1"`
            *   **`LinearLayout` (Title Row)** -> `android:id: N/A`
                *   `ImageView` (App Icon) -> `id: N/A`
                *   `TextView` (Song Title) -> `id: N/A`
            *   **`TextView` (Artist Name)** -> `id: N/A`
    *   **`LinearLayout` (Sección Historial Estático)** -> `marginTop="16dp"`
        *   **`RelativeLayout` (History Header)** -> `android:id: N/A`
            *   `TextView` (Section Label) -> `id: N/A`
            *   `ImageView` (Clear Action) -> `id: N/A`
        *   **`RelativeLayout` (Fila 1)** -> `android:id: N/A`
            *   `ImageView` (Art Thumb) -> `android:id="@+id/h1_art"`
            *   `LinearLayout` (Texts) -> `android:id: N/A`
            *   `LinearLayout` (Badge) -> `android:id="@+id/h1_badge"`
        *   **`RelativeLayout` (Fila 2)** -> `android:id: N/A`
            *   `ImageView` (Art Thumb) -> `android:id="@+id/h2_art"`

#### 2. INVENTARIO DE TOKENS DE COLOR Y TEMATIZACIÓN
| Propiedad XML | Token Asignado | Clasificación del Token |
| :--- | :--- | :--- |
| `android:background` (Raíz) | `@drawable/bg_widget_root_v31` | Shape XML (System Radius) |
| `android:tint` (Status Icon) | `@color/music_widget_on_surface_variant` | Dinámico (Sincronizado M3) |
| `android:textColor` (Status Txt) | `@color/music_widget_on_surface_variant` | Dinámico (Sincronizado M3) |
| `android:background` (Badge) | `@drawable/bg_preview_badge_pill` | Shape XML (Tertiary Container) |
| `android:tint` (Art Note) | `@color/music_widget_accent` | Dinámico (Primary Brand) |
| `android:background` (History Card)| `@drawable/bg_preview_card` | Shape XML (Surface Variant) |
| `android:textColor` (History Title)| `@color/music_widget_on_surface` | Dinámico (High Contrast) |

#### 3. INVENTARIO DE RECURSOS GRÁFICOS
| Recurso | Tipo | Rol |
| :--- | :--- | :--- |
| `@drawable/ic_device_phone` | Vector | Salida de audio representativa |
| `@drawable/mode_heat_24px` | Vector | Icono de racha |
| `@drawable/ic_preview_pill` | Vector | Molde de píldora Material |
| `@drawable/ic_music_history` | Vector | Icono unificado de visualizador Glance |

#### 4. ESTILOS TIPOGRÁFICOS Y BLINDAJE DE TEXTO
| Elemento | Estilo Aplicado | maxLines | ellipsize |
| :--- | :--- | :--- | :--- |
| Título Principal | `@style/WidgetPreview_Title` | 1 | end |
| Artista Principal | `@style/WidgetPreview_Artist` | 1 | end |
| Título Historial | `textStyle="bold"`, `textSize="13sp"` | 1 | end |
| Artista Historial | `textSize="11sp"` | 1 | end |

---

### 3.2. VARIANTE STANDARD (2x2): `widget_preview.xml`

#### 1. JERARQUÍA DE VISTAS (ÁRBOL DE NODOS)
*   **`RelativeLayout` (Raíz)** -> `android:id: N/A`
    *   **`FrameLayout` (Art)** -> `id/preview_art_container`
    *   **`ImageView` (Visualizer)** -> `id/preview_visualizer`
    *   **`LinearLayout` (Badge Area)** -> `id: N/A`
    *   **`LinearLayout` (Text Block Area)** -> `alignParentBottom="true"`

#### 2. INVENTARIO DE TOKENS Y TEXTO (2x2)
| Elemento | Token Color | Estilo Texto | maxLines |
| :--- | :--- | :--- | :--- |
| Badge Analítica | `on_tertiary_container` | N/A (Icon-Only) | N/A |
| Título Placeholder | `text_primary` | `@style/WidgetPreview_Title` | 1 |
| Artista Placeholder | `text_secondary` | `@style/WidgetPreview_Artist` | 1 |

---

### 3.3. VARIANTE PORTADA COMPLETA (2x1): `widget_preview_full.xml`

#### 1. JERARQUÍA DE VISTAS (ÁRBOL DE NODOS)
*   **`RelativeLayout` (Raíz)** -> `android:id: N/A`
    *   **`ImageView` (Visualizer Full)** -> `id/preview_visualizer_full`
    *   **`LinearLayout` (Analytics Badge)** -> `alignParentTop="true"`
    *   **`LinearLayout` (Texts)** -> `alignParentBottom="true"`

#### 2. INVENTARIO DE TOKENS Y TEXTO (2x1)
| Elemento | Token Color | Estilo Texto | maxLines |
| :--- | :--- | :--- | :--- |
| Badge | `on_tertiary_container` | Icon-Only | N/A |
| Artista | `text_secondary` | `@style/WidgetPreview_Artist` | 1 |

---

## 4. REGISTRO DE DECISIONES DE ARQUITECTURA (POR QUÉ SE HIZO ASÍ)

1.  **Eliminación de `<Space />`**: Se descartó el uso de Space porque el inflador de `RemoteViews` en versiones específicas de Android no reconoce esta etiqueta de la librería de soporte, resultando en un widget que no se añade. Se sustituyó por `android:layout_marginTop`.
2.  **Inyección por Constructor vs getReceiverName**: Se descartó detectar la identidad del widget por el nombre de la clase receptora (vía reflexión), ya que falla en el modo previsualización. Se implementó un Enum `WidgetAppearance` inyectado en el constructor de `MusicWidget`.
3.  **Transparencia de Lienzo en Vectores Legacy**: Se añadió un ítem transparente de tamaño fijo (ej. 110dp x 110dp) en la base de los `layer-list` de los esqueletos legacy. Esto evita que Launchers de Android 8-11 estiren el dibujo vectorial de forma asimétrica.
4.  **La Excepción de Redundancia en 4x4**: Se decidió eliminar el texto "Reproduciendo" del header 4x4 porque el cuerpo del widget ya contenía "¡Reproduce algo!", optimizando el escaneo visual del usuario en el selector.

---

## 5. CÓDIGO COMPLETO Y VERBATIM (VERSIONES FINALES ESTABILIZADAS)

### 5.1. Layout Historial 4x4 Rica (`res/layout-v31/widget_music_preview_4x4.xml`)
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="@dimen/widget_padding"
    android:background="@drawable/bg_widget_root_v31"
    android:theme="@style/Theme.MusicWidget"
    android:clipToOutline="true">

    <!-- 1. CABECERA UNIFICADA -->
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

    <!-- 2. BLOQUE NOW PLAYING -->
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

    <!-- 3. SECCIÓN HISTORIAL -->
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


5.2. Estilos Sincronizados (res/values/styles.xml)

    <style name="WidgetPreview_Title">
        <item name="android:textSize">@dimen/text_size_title</item>
        <item name="android:textColor">@color/music_widget_text_primary</item>
        <item name="android:textStyle">bold</item>
        <item name="android:maxLines">1</item>
        <item name="android:ellipsize">end</item>
        <item name="android:singleLine">true</item>
    </style>

    <style name="WidgetPreview_Artist">
        <item name="android:textSize">@dimen/text_size_artist</item>
        <item name="android:textColor">@color/music_widget_text_secondary</item>
        <item name="android:maxLines">1</item>
        <item name="android:ellipsize">end</item>
        <item name="android:singleLine">true</item>
    </style>

5.3. Esqueleto Sincronizado (res/layout/glance_loading_standard.xml)

<?xml version="1.0" encoding="utf-8"?>
```
<RelativeLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/music_widget_background">

    <FrameLayout
        android:id="@+id/loading_art_container"
        android:layout_width="@dimen/album_art_size_classic"
        android:layout_height="@dimen/album_art_size_classic"
        android:layout_alignParentTop="true"
        android:layout_alignParentStart="true"
        android:layout_margin="@dimen/widget_padding">
        <ImageView
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:background="@color/music_widget_skeleton"
            android:contentDescription="@null" />
    </FrameLayout>

    <ImageView
        android:layout_width="@dimen/visualizer_size"
        android:layout_height="@dimen/visualizer_size"
        android:layout_alignBottom="@id/loading_art_container"
        android:layout_alignEnd="@id/loading_art_container"
        android:layout_marginBottom="@dimen/visualizer_offset_bottom"
        android:layout_marginEnd="@dimen/visualizer_offset_end"
        android:background="@color/music_widget_skeleton"
        android:contentDescription="@null" />

    <LinearLayout
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_alignParentBottom="true"
        android:layout_alignParentStart="true"
        android:padding="@dimen/widget_padding"
        android:orientation="vertical">
        <ImageView
            android:layout_width="100dp"
            android:layout_height="12dp"
            android:background="@color/music_widget_skeleton"
            android:contentDescription="@null" />
        <ImageView
            android:layout_width="60dp"
            android:layout_height="8dp"
            android:layout_marginTop="6dp"
            android:background="@color/music_widget_skeleton"
            android:contentDescription="@null" />
    </LinearLayout>
</RelativeLayout>
```