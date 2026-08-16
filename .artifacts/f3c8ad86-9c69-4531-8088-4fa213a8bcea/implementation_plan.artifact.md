# Plan de Mejora Estética del Historial (Modo Large)

Este plan describe los cambios necesarios para separar la cabecera del historial (título y botón de limpiar) de la lista scrollable, asegurando que permanezcan fijos en la parte superior de la sección de historial.

## User Review Required

> [!NOTE]
> Este cambio solo afecta a los tamaños del widget que muestran el historial (4x2 y 4x4). La cabecera dejará de desplazarse con las canciones y se mantendrá siempre visible sobre ellas.

## Proposed Changes

### [MusicWidget](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicWidget.kt)

#### [MODIFY] [MusicWidget.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicWidget.kt)

- Refactorizar la función `HistoryList` para extraer la `Row` que contiene "HISTORIAL RECIENTE" y el botón de limpiar fuera del `LazyColumn`.
- Envolver la cabecera y el `LazyColumn` en un `Column` para mantener la estructura vertical.

## Verification Plan

### Manual Verification
- Desplegar el widget en tamaño 4x4.
- Añadir varias canciones al historial.
- Verificar que al hacer scroll en la lista de canciones, el título "HISTORIAL RECIENTE" y el botón de limpiar permanecen fijos en su posición.
- Verificar que el botón de limpiar historial sigue funcionando correctamente.
