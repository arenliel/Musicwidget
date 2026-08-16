# AUDITORÍA ANALÍTICA: COLORES Y ESTILOS DE PREVISUALIZACIÓN

Este documento proporciona una radiografía exhaustiva de los componentes visuales y tipográficos de las previsualizaciones XML activas, validando su cohesión con el sistema de diseño Material 3 y Jetpack Glance.

---

## 1. Variante Standard (2x2): `widget_preview.xml`

### Jerarquía de Vistas
`RelativeLayout` (Raíz) -> `FrameLayout` (Art) -> `ImageView` (Visualizer) -> `LinearLayout` (Badge) -> `LinearLayout` (Texts)

### Inventario de Estilos y Colores
| Elemento / Componente XML | ID o Rol Visual | Token de Color Asignado | Tipo de Token | Estado del Texto |
| :--- | :--- | :--- | :--- | :--- |
| **Contenedor Raíz** | N/A | `@color/music_widget_background` | Dinámico | N/A |
| **Píldora de Arte** | `preview_art_container` | `@drawable/ic_preview_pill` | Vector | N/A |
| **Icono Nota Central** | N/A | `@color/music_widget_accent` | Dinámico | N/A |
| **Badge de Analítica** | N/A | `@color/music_widget_on_tertiary_container` | Dinámico | `maxLines=1`, `bold` |
| **Título Canción** | N/A | `@style/WidgetPreview_Title` | Estilo M3 | `maxLines=1`, `ellipsize=end` |
| **Artista • Álbum** | N/A | `@style/WidgetPreview_Artist` | Estilo M3 | `maxLines=1`, `ellipsize=end` |

---

## 2. Variante Portada Completa (2x1): `widget_preview_full.xml`

### Jerarquía de Vistas
`RelativeLayout` (Raíz) -> `ImageView` (Visualizer) -> `LinearLayout` (Badge) -> `LinearLayout` (Texts)

### Inventario de Estilos y Colores
| Elemento / Componente XML | ID o Rol Visual | Token de Color Asignado | Tipo de Token | Estado del Texto |
| :--- | :--- | :--- | :--- | :--- |
| **Contenedor Raíz** | N/A | `@color/music_widget_background` | Dinámico | N/A |
| **Visualizador** | `preview_visualizer_full` | `@color/music_widget_accent` | Dinámico | N/A |
| **Badge de Analítica** | N/A | **`#49454F`** | ❌ **Estático** | **ANOMALÍA DETECTADA** |
| **Título Canción** | N/A | `@style/WidgetPreview_Title` | Estilo M3 | `maxLines=1`, `ellipsize=end` |
| **Artista • Álbum** | N/A | `@style/WidgetPreview_Artist` | Estilo M3 | `maxLines=1`, `ellipsize=end` |

---

## 3. Variante Historial (4x4): `widget_music_preview_4x4.xml`

### Jerarquía de Vistas
`LinearLayout` (Raíz) -> `RelativeLayout` (Cabecera) -> `LinearLayout` (Now Playing) -> `LinearLayout` (Historial)

### Inventario de Estilos y Colores
| Elemento / Componente XML | ID o Rol Visual | Token de Color Asignado | Tipo de Token | Estado del Texto |
| :--- | :--- | :--- | :--- | :--- |
| **Contenedor Raíz** | N/A | `@color/music_widget_background` | Dinámico | N/A |
| **Status Indicator** | N/A | `@color/music_widget_on_surface_variant` | Dinámico | `textSize=@dimen/text_size_status` |
| **Badge de Analítica** | N/A | `@color/music_widget_on_tertiary_container` | Dinámico | `textStyle=bold`, `10sp` |
| **Bloque Now Playing** | `preview_art_container` | `@color/music_widget_accent` (Icon) | Dinámico | `Title` y `Artist` estilizados |
| **Historial Header** | N/A | `@color/music_widget_accent` | Dinámico | `textStyle=bold`, `11sp` |
| **Fila de Historial** | `bg_preview_card` | `@color/music_widget_surface_variant` | Shape XML | `padding=8dp`, `height=48dp` |

---

## 4. DIAGNÓSTICO DE ANOMALÍAS (FLAGS)

> [!CAUTION]
> **ANOMALÍA CRÍTICA EN 2x1**: El archivo `widget_preview_full.xml` utiliza el color hexadecimal `#49454F` para el badge. Esto garantiza un fallo de legibilidad en modo oscuro y rompe la arquitectura de tokens unificados. **Acción: Parchear inmediatamente.**

> [!WARNING]
> **RIESGO DE COLAPSO EN 4x4**: La sección de Historial utiliza `layout_weight="1"`. Si el widget se escala a un tamaño menor de lo esperado, el historial podría colapsar a 0dp de altura, haciendo invisibles todos los textos de canciones previas.

> [!IMPORTANT]
> **COMPATIBILIDAD REMOTEVIEWS**: Se ha verificado la ausencia de etiquetas prohibidas como `<Space>` y `<RecyclerView>` en los tres archivos. Todos los `ImageView` decorativos tienen el atributo `contentDescription="@null"`.
