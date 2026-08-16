
# PLAN MAESTRO DE DOCUMENTACIÓN TÉCNICA: MUSIC WIDGET (VERSION 1.0)

## MÓDULO 5: COMPATIBILIDAD MULTI-API Y PREVISUALIZACIONES XML

Este módulo detalla la arquitectura de previsualizaciones estáticas, la estrategia de segmentación por niveles de API (26-35) y la ingeniería de "Skeletons" necesaria para garantizar paridad visual entre el motor Glance y el sistema de RemoteViews.

### 1. ESPECIFICACIONES TÉCNICAS DE COMPATIBILIDAD
| Parámetro | Valor / Estrategia | Justificación Técnica |
| :--- | :--- | :--- |
| **Rango de API** | 26 (Oreo) a 35 (Android 15) | Cobertura total del ecosistema moderno con optimizaciones Material You (API 31+). |
| **Estructura de Layouts** | Segmentación Base vs v31 | `layout/` para Skeletons (API <31) y `layout-v31/` para Vistas Ricas (API 31+). |
| **Fidelidad Cromática** | Tokens M3 Dinámicos | Inyección de colores `@color/music_widget_*` para evitar el bug de fondo negro en el sandbox del Launcher. |
| **Anclaje de Texto** | 32dp (Fixed Top) | Previene saltos visuales cuando el artista ocupa 2 líneas o por escalado de fuentes (A11y). |
| **Fila de Historial** | 56dp (Altura Fija) | Blindaje contra el recorte visual de nombres de artistas en el selector de widgets. |

### 2. ARQUITECTURA DE ESQUELETOS (TRACK A) VS VISTAS RICAS (TRACK B)
Para evitar el parpadeo (flicker) al añadir el widget, se implementa una sincronía milimétrica entre la carga y la previsualización.

*   **Track A (Esqueleto/Carga):** Ubicado en `res/layout/glance_loading_*.xml`. Utiliza formas abstractas y actúa como `initialLayout`.
*   **Track B (Vista Rica/Preview):** Ubicado en `res/layout-v31/widget_preview_*.xml`. Contiene textos reales y metadatos simulados para el selector de Android 12+.

### 3. CÓDIGO VERBATIM: LAYOUT HISTORIAL 4x4 (API 31+)
Este archivo (`res/layout-v31/widget_music_preview_4x4.xml`) representa la implementación más compleja, integrando la cabecera de dispositivo y el historial.

```xml
<!-- Fragmento Crítico del Contenedor de Now Playing -->
<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginTop="32dp"
    android:orientation="horizontal"
    android:gravity="center_vertical">

    <RelativeLayout
        android:layout_width="110dp"
        android:layout_height="110dp">

        <FrameLayout
            android:id="@+id/preview_art_container"
            android:layout_width="match_parent"
            android:layout_height="match_parent">
            <!-- Píldora Placeholder -->
            <ImageView
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:src="@drawable/ic_preview_pill"
                android:tint="@color/music_widget_surface_variant" />
            <!-- Nota Musical Central -->
            <ImageView
                android:layout_width="34dp"
                android:layout_height="34dp"
                android:layout_gravity="center"
                android:src="@drawable/ic_music_note"
                android:tint="@color/music_widget_accent" />
        </FrameLayout>

        <!-- Visualizador unificado con Glance -->
        <ImageView
            android:id="@+id/preview_visualizer"
            android:layout_width="24dp"
            android:layout_height="24dp"
            android:layout_alignBottom="@id/preview_art_container"
            android:layout_alignEnd="@id/preview_art_container"
            android:layout_margin="8dp"
            android:src="@drawable/ic_music_history"
            android:tint="@color/music_widget_accent" />
    </RelativeLayout>

    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:layout_marginStart="16dp"
        android:orientation="vertical">

        <TextView
            android:id="@+id/preview_title"
            style="@style/WidgetPreview_Title"
            android:text="@string/widget_empty_title"
            android:textColor="@color/music_widget_on_surface" />

        <TextView
            android:id="@+id/preview_artist"
            style="@style/WidgetPreview_Artist"
            android:text="@string/widget_empty_subtitle"
            android:textColor="@color/music_widget_on_surface_variant" />
    </LinearLayout>
</LinearLayout>
````

### 4. SINCRONIZACIÓN DE METADATOS (XML PROVIDER)

Para que el sistema resuelva dinámicamente entre el esqueleto y la vista rica, el archivo `res/xml/music_widget_info_large.xml` debe configurarse de la siguiente manera:

```
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:minWidth="300dp"
    android:minHeight="300dp"
    android:targetCellWidth="4"
    android:targetCellHeight="4"
    android:updatePeriodMillis="0"
    android:initialLayout="@layout/glance_loading_large"
    android:previewLayout="@layout/widget_music_preview_4x4"
    android:previewImage="@drawable/widget_preview_large_static"
    android:widgetCategory="home_screen"
    android:resizeMode="horizontal|vertical"
    android:description="@string/widget_description_large" />
```

### 5. MAPEO DE TOKENS MATERIAL YOU (SSOT)

Relación de colores para garantizar paridad entre Glance (Kotlin) y RemoteViews (XML).

|Elemento Visual|Token Glance (Kotlin)|Recurso XML (v31)|
|:--|:--|:--|
|**Fondo Raíz**|`widgetBackground`|`@android:color/system_neutral2_800`|
|**Título**|`onSurface`|`@android:color/system_neutral1_50`|
|**Artista / Estatus**|`onSurfaceVariant`|`@android:color/system_neutral2_200`|
|**Tarjeta Historial**|`surfaceVariant`|`@android:color/system_neutral2_700`|
|**Acento (Nota/Badge)**|`primary`|`@color/music_widget_accent`|

### 6. LISTA NEGRA DE PRÁCTICAS (ANTI-PATRONES)

- **PROHIBIDO: Uso de `<View />` o `<Space />`.** RemoteViews no soporta la clase base `View`. Causará errores de inflado catastróficos. Usar `ImageView` o `layout_margin`.
- **PROHIBIDO: Uso de `tools:text`.** El sistema ignora este namespace en el selector de widgets real, resultando en campos vacíos. Usar siempre `android:text`.
- **PROHIBIDO: Atributos `?android:attr` en Previews.** El modo sandbox del Launcher no puede resolver atributos de tema dinámicos en tiempo de selección, resultando en fondos negros. Usar la paleta de `@color` propia.
- **PROHIBIDO: `android:tint` en Vectores XML.** Los widgets de Glance no pueden sobrescribir tintes fijos en el XML. Se debe eliminar el tinte del XML y aplicarlo programáticamente vía Kotlin.
- **PROHIBIDO: `SizeMode.Responsive`.** Provoca "ghost padding" y saltos visuales bruscos que rompen la fidelidad de la previsualización. Usar siempre `SizeMode.Exact`.

---

**FIN DEL MÓDULO 5**