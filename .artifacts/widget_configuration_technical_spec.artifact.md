# Especificación Técnica: Pantalla de Configuración del Widget (WidgetConfigActivity)

Este documento detalla la implementación, lógica y arquitectura de la actividad de configuración para los widgets de Music Widget, basada en **Jetpack Compose** y **Jetpack Glance**.

## 1. Declaración y Vínculo (AndroidManifest & XML)

La actividad `WidgetConfigActivity` actúa como el punto central de personalización y diagnóstico para todas las variantes del widget.

### Manifiesto
Se declara con el intent-filter `APPWIDGET_CONFIGURE` para que el sistema Android la reconozca como la pantalla de configuración del widget.
```xml
<activity
    android:name=".WidgetConfigActivity"
    android:exported="true"
    android:theme="@style/Theme.MusicWidget">
    <intent-filter>
        <action android:name="android.appwidget.action.APPWIDGET_CONFIGURE" />
    </intent-filter>
</activity>
```

### Metadatos del Widget (AppWidgetProviderInfo)
Cada variante del widget (`Full`, `Pill`, `Control`) referencia a esta actividad mediante el atributo `android:configure` y habilita la reconfiguración posterior.
- **Archivos:** `music_widget_info_full.xml`, `music_widget_info_pill.xml`, `music_widget_info_control.xml`.
- **Atributos Clave:**
  - `android:configure="arenliel.musicwidget.WidgetConfigActivity"`
  - `android:widgetFeatures="reconfigurable"`

## 2. Estrategia de Configuración Determinista

### Aceptación Inmediata (Gesture-Friendly)
Para evitar que el widget sea cancelado accidentalmente por gestos de navegación hacia atrás en Android 10+, la actividad informa al sistema que el resultado es `RESULT_OK` inmediatamente en el `onCreate`.
```kotlin
if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
    val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
    setResult(Activity.RESULT_OK, resultValue)
}
```

## 3. Pilares de Funcionalidad

La UI está dividida en tres secciones críticas:

### A. Gestión de Permisos (Seguridad)
El widget depende de dos permisos vitales:
1.  **Acceso a Notificaciones:** Necesario para leer la metadata musical vía `NotificationListenerService`.
2.  **Optimización de Batería:** Necesario para garantizar que el servicio no sea matado por el sistema en segundo plano.

**Mecanismos Especiales:**
- **Polling de Estado:** La actividad utiliza un `DisposableEffect` con el ciclo de vida para verificar si los permisos fueron concedidos al regresar de la pantalla de Ajustes del Sistema.
- **Soporte para Ajustes Restringidos:** Incluye una tarjeta informativa (`RestrictedSettingsCard`) para guiar al usuario en dispositivos con restricciones de seguridad de Android 13+ (apps instaladas vía APK).

### B. Lista Blanca de Aplicaciones (Comportamiento)
Permite al usuario filtrar qué aplicaciones de música deben ser procesadas por el widget.
- **Persistencia:** Utiliza `MusicDataStore` para guardar una lista negra (`blacklist`).
- **Filtrado:** La lista de aplicaciones instaladas se filtra mediante `MediaBrowserService` y categorías de audio/video.
- **Actualización Delegada:** Al cambiar la lista, se invoca `WidgetAppearance.update(context, glanceId)` para reflejar el cambio en el widget específico de forma inmediata.

### C. Diagnóstico Técnico
Incluye una hoja inferior (`DiagnosticSheetContent`) que permite visualizar y copiar el archivo `widget_error.log`. Esto es fundamental para depurar errores de renderizado de Glance en producción.

## 4. Integración con el Motor del Widget

La actividad utiliza el enum `WidgetAppearance` (SSOT - Single Source of Truth) para interactuar con los widgets de Glance de forma segura.

| Acción | Método / Clase | Descripción |
| :--- | :--- | :--- |
| **Obtener Identidad** | `appWidgetInfo.provider.className` | Identifica si el widget es `Full`, `Control` o `Standard`. |
| **Actualizar Widget** | `appearance.update(context, glanceId)` | Dispara el ciclo de recomposición de Glance tras un cambio de config. |
| **Sincronización** | `GlanceAppWidgetManager(context)` | Obtiene el `glanceId` a partir del `appWidgetId` tradicional. |

## 5. Referencias de Código
- **Actividad Principal:** [WidgetConfigActivity.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/WidgetConfigActivity.kt)
- **Persistencia:** [MusicDataStore.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicDataStore.kt)
- **Utilidades de Permiso:** [PermissionUtils.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/PermissionUtils.kt)
- **Definiciones XML:** [res/xml/](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/res/xml/)

---
*Documento actualizado al 11 de agosto de 2026.*
