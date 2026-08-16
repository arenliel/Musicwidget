# Plan de Implementación: Corrección de Falsos Skips (Lógica de Marca de Agua Alta)

Este plan aborda el error donde canciones completadas se marcan como skips debido a reinicios de posición del reproductor, implementando una memoria de progreso máximo.

## Proposed Changes

### [Service] [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt)

#### [MODIFY] `MediaSnapshot`
- Añadir campo `maxPositionMs: Long`.

#### [MODIFY] `processSnapshot`
- Implementar la herencia de progreso: `maxPositionMs` será el valor máximo entre la posición actual y el valor guardado en el snapshot anterior (si es la misma canción).
- Utilizar `maxPositionMs` para el cálculo del 70% antes de enviar al historial.

## Verification Plan

### Manual Verification
1.  **Reproducción Final:** Dejar que una canción termine normalmente. Verificar que no aparece el icono de skip (incluso si Spotify resetea a 0ms al final).
2.  **Retroceso (Seeking Backward):** Escuchar el 80% de una canción, retroceder al 20% y saltar a la siguiente. Verificar que **no** aparece el icono de skip (porque ya llegó al 80%).
3.  **Adelanto (Seeking Forward):** Saltar directamente al 75% de la canción y pasar a la siguiente. Verificar que aparece como completada.
