# Plan de Implementación: Ergonomía M3 y Thumbzone para WidgetConfigActivity

Este plan detalla los cambios necesarios para alinear la pantalla de configuración con los estándares de **Material 3 Expressive**, mejorando la usabilidad con una sola mano (Thumbzone) y refinando el sistema de componentes conectados.

## Cambios Propuestos

### Componente: WidgetConfigActivity

Se transformará la estructura actual de `Column` simple a un `Scaffold` con `LargeTopAppBar` para implementar la "zona de alcance".

#### [MODIFY] [WidgetConfigActivity.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/WidgetConfigActivity.kt)

1.  **Refactorización de Layout:**
    *   Sustituir `Surface` + `Column` por `Scaffold`.
    *   Implementar `LargeTopAppBar` con `TopAppBarDefaults.exitUntilCollapsedScrollBehavior()`.
    *   Configurar el título "Ajustes de música" dentro del `LargeTopAppBar`.
2.  **Ajustes de Spacing y Márgenes:**
    *   Reducir el padding horizontal de `20.dp` a `16.dp` (Estándar M3).
    *   Eliminar los `Spacer` manuales de cabecera, delegando el espacio al comportamiento del `TopAppBar`.
3.  **Refinamiento de Connected Shapes:**
    *   Actualizar `PermissionCard` y `BatteryOptimizationCard` para usar radios de **28.dp** (exterior) y **4.dp** (interior).
    *   Normalizar la altura mínima de las tarjetas a **72.dp** para mejorar el ritmo visual.

## Plan de Verificación

### Verificación Visual (Manual)
1.  **Estado Inicial:** El título debe aparecer grande en la parte superior, empujando las tarjetas hacia el centro de la pantalla (Thumbzone).
2.  **Scroll:** Al deslizar hacia arriba, el título debe colapsar suavemente a una barra estándar.
3.  **Connected Shapes:** Las tarjetas de permiso deben verse "conectadas" con una separación mínima (2dp) y esquinas internas casi rectas (4dp).

### Verificación Técnica
1.  **Build:** Asegurar que el proyecto compile con las nuevas APIs de `ExperimentalMaterial3Api` necesarias para `LargeTopAppBar`.
2.  **Funcionalidad:** Confirmar que los botones de "Activar" y "Ajustar" siguen funcionando y que el polling de permisos no se ve afectado.
