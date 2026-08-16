# Plan de Implementación: Onboarding Dinámico y Dashboard Centralizado (v2.0)

Este plan detalla la transformación de `MainActivity` en el núcleo de la aplicación, gestionando el ciclo de vida desde la instalación fresca (Onboarding) hasta el uso diario (Dashboard de Aplicaciones), manteniendo la paridad técnica con los sistemas de previsualización y seguridad existentes.

## User Review Required

> [!IMPORTANT]
> - **Icono de App:** Seguirá permitiendo el anclaje del widget, pero ahora servirá como base para la configuración inicial.
> - **Nomenclatura UI:** He seleccionado **"Tus aplicaciones"** para la lista blanca y **"¡Todo listo!"** para el botón de finalización.
> - **Flujo Ininterrumpido:** Se mantiene el uso de trampolines para que los cambios en ajustes del sistema se reflejen al instante en la UI sin cierres manuales.
> - **Detección Defensiva:** El widget mostrará el estado de error si **cualquiera** de los dos permisos (Notificaciones o Batería) es revocado.

## Cambios Propuestos

### 0. Localización y Recursos (i18n)
*   **[MODIFY] [strings.xml](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/res/values/strings.xml)**
    *   Extraer todos los textos del Onboarding, Dashboard y Ajustes a recursos de cadena. Esto evitará el "hardcoding" y permitirá traducciones futuras de forma nativa.

### 1. MainActivity: El Corazón de la App
#### [MODIFY] [MainActivity.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MainActivity.kt)
*   **Identidad Visual:** Título "Primeros pasos" con `LargeTopAppBar` e integración total M3 Expressive.
*   **Lógica de Estado (Switching):**
    *   `Estado A (Onboarding)`: Si faltan permisos críticos. Muestra las tarjetas con Switch (Batería) y Botón (Notificaciones) + Tarjeta de Seguridad APK.
    *   `Estado B (Post-Setup)`: Una vez concedidos los permisos, la UI transiciona a mostrar **"Tus aplicaciones"** (Lista blanca).
*   **Copywriting Refinado:**
    *   **Batería (Switch):** "Actividad en segundo plano" -> "Para que el widget nunca se detenga y muestre siempre la canción actual, necesitamos que la app ignore las restricciones de batería."
    *   **Notificaciones (Botón):** "Conexión musical" -> "Music Widget necesita leer tus notificaciones para extraer la carátula, el artista y permitirte controlar la reproducción."
    *   **Seguridad (Info Card):** "Acceso protegido" -> "Por tu privacidad, Android limita el acceso en apps externas. Si no puedes activar los permisos, abre la 'Información de la app' y selecciona 'Permitir ajustes restringidos' en el menú superior."
*   **Acción de Anclaje:** Mantendrá el comportamiento de solicitar el anclaje del widget al abrirse desde el launcher, pero de forma no bloqueante para la UI.
*   **Botón de Cierre:** Botón M3 Expressive **"¡Todo listo!"** que aparece al completar el setup y devuelve al usuario al Home.

### 2. Widget: Enrutamiento Inteligente
#### [MODIFY] [MusicWidget.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicWidget.kt)
*   **Sensado de Fallo:** Se mostrará `PermissionsView` si `!notificationsEnabled || !batteryOptimized`.
*   **Cambio de Intent:** La `PermissionsView` ahora redirigirá a `MainActivity` en lugar de `WidgetConfigActivity`.
*   **Protección Anti-NaN:** Se asegura que el renderizado de Glance sea defensivo contra dimensiones indeterminadas.

### 3. WidgetConfigActivity: Simplificación
#### [MODIFY] [WidgetConfigActivity.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/WidgetConfigActivity.kt)
*   Se eliminarán las tarjetas de permisos del flujo de configuración del widget.

## Plan de Verificación

### Escenarios Críticos
1.  **Instalación Fresca:** Tocar icono -> Ver Onboarding -> Conceder permisos -> Ver Lista de Apps -> Tocar "¡Todo listo!" -> Home.
2.  **Widget sin Permisos:** Tocar widget en error -> Ver Onboarding en `MainActivity`.
3.  **Uso Diario:** Tocar icono (con permisos OK) -> Ver Dashboard (Tus Aplicaciones) directamente.
4.  **Revocación:** Quitar permisos en Ajustes -> Tocar widget -> `MainActivity` detecta el fallo y muestra de nuevo el Onboarding.
5.  **Launcher Sandbox:** Verificar que las previsualizaciones XML del widget no se rompen por cambios en el flujo de configuración.
