# Plan de Sincronía Total de Letras (Sync-Infinity)

Este plan aborda la desincronización de letras causada por saltos hacia atrás (Seek) y pausas prolongadas, transformando el scheduler actual en un motor reactivo de alta fidelidad.

## User Review Required

> [!IMPORTANT]
> Se eliminará la lógica de `maxPositionMs` como restricción de progreso para permitir que el widget retroceda instantáneamente cuando el usuario lo haga en su reproductor. Esto es vital para la precisión pero requiere que los `MediaController` de las apps (Spotify, etc.) envíen datos coherentes.

## Proposed Changes

### [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt)

#### [MODIFY] calculateEffectiveProgress
- Eliminar `Math.max(snapshot.maxPositionMs, estimatedPos)`.
- La posición estimada se basará estrictamente en `snapshot.positionMs + delta`, permitiendo valores menores que registros anteriores si el snapshot así lo indica.

#### [MODIFY] processSnapshot
- Refinar la detección de Seek para que, si el cambio de posición es significativo (> 2s), se fuerce el reinicio de `startLyricsShowcase` sin importar si el `contentKey` parece similar.

#### [MODIFY] startLyricsShowcase
- Introducir un `Trigger` de actualización. En lugar de un `while` con `delay` ciego, usaremos un flujo o un mecanismo de señales que permita cancelar la espera actual y recalcular si llega un nuevo Snapshot de posición.

#### [MODIFY] startSeekEventProcessor
- Asegurar que al aplicar un Seek consolidado, se invoque `processSnapshot` o se emita una señal que reinicie el scheduler de letras.

## Verification Plan

### Manual Verification
1. **Prueba de Salto Atrás:** Reproducir una canción, esperar al minuto 1:00, retroceder manualmente al segundo 0:10.
   - *Resultado esperado:* La letra en el widget debe cambiar al segundo 0:10 instantáneamente y continuar desde ahí.
2. **Prueba de Reanudación:** Pausar la música por 30 segundos y reanudar.
   - *Resultado esperado:* Las letras deben sincronizarse inmediatamente al reanudar sin esperar al siguiente checkpoint.
3. **Prueba de Cambio de Track Rápido:** Cambiar varias canciones seguidas.
   - *Resultado esperado:* No deben quedar "fantasmas" de letras de la canción anterior.
