# CHANGELOG: OPTIMIZACIÓN DE PREVISUALIZACIONES XML

Este documento detalla la reingeniería de las vistas previas estáticas para garantizar paridad técnica con Jetpack Glance y cumplimiento con Material Design 3.

---

## 1. RECURSOS DE COLOR Y TEMATIZACIÓN
Se han estandarizado los tokens para soportar la paleta de analíticas (Terciaria) y tarjetas de historial (Surface Variant).

### [MODIFICACIÓN] [colors.xml](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/res/values/colors.xml)
```xml
+   <color name="music_widget_surface_variant">#E7E0EC</color>
+   <color name="music_widget_on_surface_variant">#49454F</color>
+   <color name="music_widget_tertiary_container">#FFD8E4</color>
+   <color name="music_widget_on_tertiary_container">#31111D</color>
```
> **Justificación:** Inyectar tokens específicos de M3. El uso de `tertiaryContainer` es obligatorio para los badges de analítica para evitar colisiones visuales con los colores primarios de la marca.

---

## 2. COMPONENTES GRÁFICOS (DRAWABLES)
Se crearon archivos de forma para emular los modificadores `.cornerRadius()` de Glance.

### [NUEVO] [bg_preview_card.xml](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/res/drawable/bg_preview_card.xml)
```xml
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/music_widget_surface_variant" />
    <corners android:radius="16dp" />
</shape>
```
> **Justificación:** Réplica exacta de la tarjeta `HistoryItemRow` definida en Kotlin.

### [NUEVO] [bg_preview_badge_pill.xml](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/res/drawable/bg_preview_badge_pill.xml)
```xml
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/music_widget_tertiary_container" />
    <corners android:radius="100dp" />
</shape>
```
> **Justificación:** Implementación de la forma "cápsula" para badges de analítica (`streak` y `playsToday`).

---

## 3. LAYOUT DE PREVISUALIZACIÓN 4x4
Sustitución del layout simplista por una construcción 1:1 con el Composable `Layout4x4`.

### [NUEVO] [widget_music_preview_4x4.xml](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/res/layout/widget_music_preview_4x4.xml)
**Bloque de Cabecera (Verbatim):**
```xml
<RelativeLayout android:layout_width="match_parent" android:layout_height="wrap_content">
    <LinearLayout ... android:layout_alignParentStart="true">
        <ImageView ... android:src="@drawable/ic_device_bluetooth" />
        <TextView ... tools:text="• Reproduciendo" />
    </LinearLayout>
    <LinearLayout ... android:layout_alignParentEnd="true" android:background="@drawable/bg_preview_badge_pill">
        <ImageView ... android:src="@drawable/mode_heat_24px" />
        <TextView ... tools:text="3d" />
    </LinearLayout>
</RelativeLayout>
```
> **Justificación:** Se añadió el `PlaybackStatusIndicator` y el `DesignBadge` superior, que estaban ausentes en la versión previa.

**Simulación de Historial:**
Se implementaron dos bloques estáticos de `RelativeLayout` con `android:background="@drawable/bg_preview_card"` para representar las filas del historial sin usar adaptadores.

---

## 4. METADATOS Y VÍNCULOS
Corrección de la configuración del proveedor del widget.

### [MODIFICACIÓN] [music_widget_info_control.xml](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/res/xml/music_widget_info_control.xml)
```diff
-   android:targetCellHeight="2"
+   android:targetCellHeight="4"
-   android:previewLayout="@layout/widget_preview_large"
+   android:previewLayout="@layout/widget_music_preview_4x4"
```
> **Justificación:** El widget "Control" está diseñado para ser 4x4. La configuración 4x2 causaba que el sistema recortara la sección del historial en el selector de widgets.

---

## 5. VALIDACIÓN EN CÓDIGO (PREVIEWS)

### [MODIFICACIÓN] [MusicWidget.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicWidget.kt)
```kotlin
@Preview(widthDp = 340, heightDp = 340)
@Composable
fun XML_Preview_Large_4x4() {
    val context = LocalContext.current
-   AndroidRemoteViews(remoteViews = RemoteViews(context.packageName, R.layout.widget_preview_large))
+   AndroidRemoteViews(remoteViews = RemoteViews(context.packageName, R.layout.widget_music_preview_4x4))
}
```
> **Justificación:** Asegurar que la herramienta de diseño de Android Studio muestre el nuevo layout optimizado al desarrollar en Glance.
