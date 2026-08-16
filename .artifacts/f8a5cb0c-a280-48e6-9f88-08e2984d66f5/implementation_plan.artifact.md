# Plan de Corrección de Previsualización 4x2 (Large)

El objetivo es recuperar la previsualización del widget "Large" (4x2) en el selector, resolviendo el error "no se pudo agregar el widget" mientras se mantiene la fidelidad visual y los colores dinámicos.

## User Review Required

> [!IMPORTANT]
> Se ha detectado que el diseño actual de la previsualización 4x2 intentaba mostrar más contenido (Arte + Historial) del que físicamente cabe en la altura de 110dp de un widget de 2 filas. Esto causaba un desbordamiento que el sistema rechazaba. Se propone un diseño horizontal optimizado para este tamaño.

## Proposed Changes

### [Component] UI / Previews

#### [MODIFY] [widget_preview_large.xml](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/res/layout/widget_preview_large.xml)
- Rediseñar para ser una vista horizontal pura (Arte a la izquierda, Texto a la derecha).
- Eliminar la sección de historial que causaba desbordamiento vertical.
- Mantener `android:theme="@style/Theme.MusicWidget"` para asegurar colores dinámicos.

#### [MODIFY] [preview_background.xml](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/res/drawable/preview_background.xml)
- Añadir un fallback para el radio de las esquinas para evitar fallos en dispositivos pre-API 31.

#### [MODIFY] [music_widget_info_large.xml](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/res/xml/music_widget_info_large.xml)
- Ajustar `initialLayout` para que coincida con el tamaño esperado del widget.

### [Component] Glance Logic

#### [MODIFY] [MusicWidget.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicWidget.kt)
- Ajustar la lógica de `Layout4x4` (ahora usada para 4x2) para que oculte el historial si la altura es insuficiente (< 150dp), manteniendo paridad con la previsualización.

## Verification Plan

### Automated Tests
- No aplica (UI/XML).

### Manual Verification
1. Abrir el selector de widgets.
2. Verificar que el widget "Large" (4x2) aparece correctamente.
3. Comprobar que mantiene los colores dinámicos del sistema (Material You).
4. Verificar que el diseño horizontal es limpio y no muestra el error genérico.
