# Plan de Implementación: Corrección de Persistencia de Icono y Sincronización de Llaves

Este plan aborda el error donde el widget mantiene el icono de una sesión anterior (como KDE Connect) al cambiar de aplicación, y corrige la discrepancia entre el código y la documentación sobre la generación de la llave digital (`trackKey`).

## User Review Required

> [!IMPORTANT]
> Se ajustará la lógica de `isAppAllowed` para ser más estricta con aplicaciones de sistema que no son reproductores reales, lo cual podría afectar la visibilidad de algunas apps que antes "se colaban".

## Proposed Changes

### [Component Name] Music Listener & Data Logic

#### [MODIFY] [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt)
- **Corrección de `trackKey`**: Se incluirá `durationMs` en la generación de la llave, cumpliendo con la documentación técnica y mejorando la unicidad.
- **Corrección de limpieza de icono**: Se modificará la lógica en `processSnapshot` para asegurar que `savedAppIconKey` y el archivo en disco se limpien SIEMPRE que cambie la pista y no se encuentre un nuevo icono. Actualmente, el sistema mantiene la llave anterior si falla la resolución del nuevo icono.
- **Refinamiento de `isAppAllowed`**: Se añadirá una lista de exclusión (Blacklist interna) para evitar que apps de sistema como KDE Connect o navegadores ensucien el widget si no se desea, o al menos asegurar que su ciclo de vida no afecte a las apps principales.

#### [MODIFY] [MusicWidget.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicWidget.kt)
- Se verificará que la lógica de renderizado del icono maneje correctamente el estado de "llave vacía" para forzar el uso del icono de la aplicación desde el `PackageManager`.

## Verification Plan

### Automated Tests
- No se dispone de tests automatizados de UI en este momento, se procederá con verificación manual.

### Manual Verification
1. Reproducir algo en Spotify.
2. Iniciar una sesión en una app "intrusa" (KDE Connect/WhatsApp Web). Verificar que el widget se actualice (o ignore según la nueva lista).
3. Cerrar la sesión intrusa y volver a Spotify.
4. Verificar que el icono cambie correctamente al de Spotify (o al icono genérico de la app si la notificación no está disponible) y no se quede pegado el de la app anterior.
5. Inspeccionar logs para confirmar `COMMIT_ZONE` con las llaves correctas.
