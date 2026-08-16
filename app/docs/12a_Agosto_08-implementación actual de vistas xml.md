# DOCUMENTO MAESTRO: ESPECIFICACIONES TÉCNICAS Y BUENAS PRÁCTICAS PARA PREVISUALIZACIONES XML (MUSIC WIDGET)

Este documento establece el estándar de ingeniería para la implementación de previsualizaciones estáticas y esqueletos de carga del Music Widget, garantizando paridad total con el motor de renderizado de Jetpack Glance y compatibilidad multi-API (26-35) [1, 2].

## 1. ESPECIFICACIÓN TÉCNICA DE LAS 3 APARIENCIAS

El sistema se divide en tres identidades atómicas inyectadas mediante el constructor de cada Receiver para evitar errores de reflexión en el selector de widgets [3].

| Atributo | **Small (2x1)** | **Standard (2x2)** | **Large (4x4)** |
| :--- | :--- | :--- | :--- |
| **Variante** | Full-Bleed Art [4, 5] | Píldora Clásica [4, 5] | Centro de Control / Historial [4, 6] |
| **Info XML** | `music_widget_info_small.xml` [7] | `music_widget_info.xml` [8, 9] | `music_widget_info_large.xml` [7, 9] |
| **Layout XML** | `widget_preview_small.xml` [7, 10] | `widget_preview.xml` [10, 11] | `widget_preview_large.xml` [7, 10] |
| **Dimensiones** | 2x1 (140dp x 70dp aprox.) [12] | 2x2 (140dp x 140dp aprox.) [8] | 4x4 (300dp x 300dp aprox.) [7] |
| **Celdas Target** | `targetCellWidth="2"`, `targetCellHeight="1"` | `targetCellWidth="2"`, `targetCellHeight="2"` | `targetCellWidth="4"`, `targetCellHeight="4"` [9] |
| **Atributos Preview** | `previewLayout`, `previewImage` [9] | `previewLayout`, `previewImage` [9] | `previewLayout`, `previewImage` [9] |

---

## 2. CÓDIGO XML LITERAL Y COMPLETO (VERBATIM)

### A. Esqueleto de Carga (res/layout/glance_loading_large.xml)
Este archivo actúa como `initialLayout` y fallback de seguridad. Utiliza `ImageView` para evitar errores de inflado en RemoteViews [13, 14].

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/music_widget_background"
    android:padding="17dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="bottom">

        <ImageView
            android:layout_width="110dp"
            android:layout_height="110dp"
            android:src="@drawable/preview_skeleton_pill"
            android:contentDescription="@null" />

        <LinearLayout
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:orientation="vertical"
            android:layout_marginStart="22dp">

            <ImageView
                android:layout_width="120dp"
                android:layout_height="16dp"
                android:background="@color/music_widget_on_surface_variant"
                android:alpha="0.3"
                android:contentDescription="@null" />

            <ImageView
                android:layout_width="80dp"
                android:layout_height="12dp"
                android:layout_marginTop="8dp"
                android:background="@color/music_widget_on_surface_variant"
                android:alpha="0.1"
                android:contentDescription="@null" />
        </LinearLayout>
    </LinearLayout>
</FrameLayout>
B. Previsualización Rica (res/layout-v31/widget_preview_large.xml)
Diseño corregido para evitar desbordamientos en el launcher, utilizando el eje horizontal para el tamaño 4x2/4x4
.
<?xml version="1.0" encoding="utf-8"?>
<RelativeLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/music_widget_background"
    android:padding="17dp">

    <ImageView
        android:id="@+id/preview_art"
        android:layout_width="110dp"
        android:layout_height="110dp"
        android:layout_alignParentBottom="true"
        android:src="@drawable/ic_music_note"
        android:background="@drawable/preview_skeleton_pill"
        android:contentDescription="@null" />

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_toEndOf="@id/preview_art"
        android:layout_alignParentBottom="true"
        android:layout_marginStart="22dp"
        android:orientation="vertical">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Título de canción"
            style="@style/WidgetPreview_Title" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Artista • Álbum"
            android:textColor="@color/music_widget_on_surface_variant"
            android:textSize="12sp" />
    </LinearLayout>
</RelativeLayout>
3. HISTORIAL DE ERRORES Y PRÁCTICAS INCORRECTAS
Basado en las auditorías de desarrollo, se prohíbe el uso de las siguientes configuraciones por causar inestabilidad o fallos críticos:
USO DE SizeMode.Responsive: Genera "ghost padding" y saltos visuales bruscos al no coincidir con los tamaños reportados por el Launcher (ej. 104dp vs 110dp). Solución: Usar siempre SizeMode.Exact.

ATRIBUTOS ?android:attr EN PREVIEWS: Los selectores de widgets en modo "sandbox" a menudo fallan al resolver atributos dinámicos, resultando en fondos negros. Solución: Inyectar colores directos de la paleta propia (@color/music_widget_background) sincronizados en res/values-v31/.

ETIQUETA <View /> EN XML: RemoteViews no soporta la clase base View, provocando un error de inflado silencioso que deja el widget en blanco. Solución: Sustituir por ImageView con contentDescription="@null".

android:tint CON ATRIBUTOS DE TEMA: Causa un error de vinculación de recursos (Resource linking failed) porque Glance no puede acceder a ellos en tiempo de renderizado. Solución: Aplicar el tinte programáticamente en Kotlin con ColorFilter.

---

## 5. ARQUITECTURA DE FIDELIDAD CROMÁTICA (SENIOR PRINCIPLES)

Para garantizar paridad total entre Glance y XML sin errores de renderizado en el Launcher:

1. **Sustitución de Atributos de Sistema por Colores Propios**: Se prohíbe el uso de atributos `?android:attr` en las vistas previas XML. Se deben usar colores de la paleta propia (@color/music_widget_*) alojados en carpetas calificadas (v31) para mantener dinamismo con estabilidad.
2. **Sincronización de la "Fuente de Verdad"**: Unificación de tokens entre `GlanceTheme.colors` en Kotlin y los recursos XML en `res/layout-v31/`.
3. **Prohibición de Tintado Estático en XML**: Eliminar `android:tint` de los archivos vectoriales XML. El color debe aplicarse programáticamente en Kotlin mediante `ColorFilter.tint()`.
4. **Mapeo Determinista de Tokens**: 
    - `widgetBackground`: Fondo y scrim.
    - `onSurface`: Títulos (Máxima jerarquía).
    - `onSurfaceVariant`: Artista y estados secundarios.
    - `primary`: Acentos y letras sincronizadas.
    - `primaryContainer`: Píldoras de dispositivo de salida.
