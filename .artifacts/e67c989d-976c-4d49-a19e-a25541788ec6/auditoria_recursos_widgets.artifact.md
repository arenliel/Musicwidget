# AUDITORÍA TÉCNICA: RECURSOS DE PREVISUALIZACIÓN DE WIDGETS

Este documento presenta una radiografía exacta de los componentes XML utilizados para el renderizado de previsualizaciones.

---

## 1. widget_music_preview_4x4.xml (Variante Historial)

### JERARQUÍA DE VISTAS (ÁRBOL)
- `LinearLayout` (Raíz)
  - `RelativeLayout` (Cabecera)
    - `LinearLayout` (Status) -> `ImageView`, `TextView`
    - `LinearLayout` (Badge) -> `ImageView`, `TextView`
  - `LinearLayout` (Now Playing)
    - `ImageView` (Carátula)
    - `LinearLayout` (Textos) -> `TextView`, `TextView`
  - `LinearLayout` (Historial)
    - `RelativeLayout` (Header) -> `TextView`, `ImageView`
    - `RelativeLayout` (Fila 1) -> `ImageView`, `LinearLayout` (`TextView`, `TextView`), `LinearLayout` (`ImageView`, `TextView`)
    - `RelativeLayout` (Fila 2) -> `ImageView`, `LinearLayout` (`TextView`, `TextView`), `LinearLayout` (`ImageView`, `TextView`)

### INVENTARIO DE TOKENS DE COLOR Y TEMATIZACIÓN
| Propiedad | Valor | Tipo |
| :--- | :--- | :--- |
| `android:background` (Raíz) | `@color/music_widget_background` | Token Dinámico |
| `android:tint` (Status Icon) | `@color/music_widget_on_surface_variant` | Token Dinámico |
| `android:textColor` (Status) | `@color/music_widget_on_surface_variant` | Token Dinámico |
| `android:background` (Badge) | `@drawable/bg_preview_badge_pill` | Shape XML |
| `android:textColor` (Badge) | `@color/music_widget_on_tertiary_container` | Token Dinámico |
| `android:textColor` (Artista) | `@color/music_widget_text_secondary` | Token Dinámico |
| `android:textColor` (Hist. Header)| `@color/music_widget_accent` | Token Dinámico |

### INVENTARIO DE DRAWABLES E IMÁGENES
| Elemento | Recurso | Tipo |
| :--- | :--- | :--- |
| Icono Status | `@drawable/ic_device_bluetooth` | Vector |
| Icono Badge | `@drawable/mode_heat_24px` | Vector |
| Carátula | `@drawable/ic_preview_pill` | Vector Placeholder |
| Fondo Carátula | `@drawable/bg_preview_art_16dp` | Shape XML |
| Tarjeta Fila | `@drawable/bg_preview_card` | Shape XML |

### DETECCIÓN DE ANOMALÍAS (FLAGS)
- ✅ **RemoteViews Safe**: No se detectaron etiquetas `<Space>`, `<View>` o `<RecyclerView>`.
- ✅ **Accesibilidad**: Todos los `ImageView` poseen `android:contentDescription="@null"`.
- ✅ **Protección Texto**: Todos los `TextView` implementan `maxLines="1"` y `ellipsize="end"`.

---

## 2. widget_preview.xml (Variante Standard 2x2)

### JERARQUÍA DE VISTAS (ÁRBOL)
- `RelativeLayout` (Raíz)
  - `FrameLayout` (Art Container) -> `ImageView` (Pill), `ImageView` (Note)
  - `ImageView` (Visualizer)
  - `LinearLayout` (Badge) -> `ImageView`, `TextView`
  - `LinearLayout` (Texts) -> `LinearLayout` (`ImageView`, `TextView`), `TextView`

### INVENTARIO DE TOKENS DE COLOR Y TEMATIZACIÓN
| Propiedad | Valor | Tipo |
| :--- | :--- | :--- |
| `android:background` (Raíz) | `@color/music_widget_background` | Token Dinámico |
| `android:tint` (Visualizer) | `@color/music_widget_accent` | Token Dinámico |
| `android:textColor` (Badge) | `#49454F` | **Estático (Riesgo bajo)** |

### DETECCIÓN DE ANOMALÍAS (FLAGS)
- ✅ **RemoteViews Safe**: Limpio.
- ✅ **Accesibilidad**: Limpio.
- ✅ **Protección Texto**: Heredada mediante estilos `@style/WidgetPreview_*`.

---

## 3. widget_preview_large.xml (ARCHIVO LEGADO - ALERTA)

### DETECCIÓN DE ANOMALÍAS (FLAGS)
- ❌ **ANOMALÍA CRÍTICA**: Uso de `?android:attr/textColorSecondary` (Línea 30).
- ❌ **ANOMALÍA CRÍTICA**: Uso de `?android:attr/textColorPrimary` (Línea 75).
- ❌ **ANOMALÍA CRÍTICA**: Uso de `?android:attr/colorPrimary` (Línea 106).
- ❌ **ACCESIBILIDAD**: Múltiples `ImageView` sin `contentDescription` definido.
- ⚠️ **RECOMENDACIÓN**: Este archivo debe ser eliminado para evitar fallos de renderizado en el selector de widgets.

---
**Auditoría finalizada.**
