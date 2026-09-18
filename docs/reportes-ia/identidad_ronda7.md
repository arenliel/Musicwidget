# Auditoría — Ronda 7 (Identidad): Fórmula Completa de `sessionEnded` y Qué Pasa con un Rebobinado

## Evidencia de Git
**Comando:** `git log --oneline -3`
```text
3e85a7a (HEAD -> master) Conjunto Artwork-Final: Reemplazar previousApplied por previousLogical para sanear la detección de cambios
dcb4f54 Conjunto Artwork-Trace: Instrumentación de sincronía de archivos de portada
699229c Conjunto Artwork-Confirm: Registro de confirmación de persistencia real
```

---

## AV1. Fórmula completa y exacta de `sessionEnded`

**Archivo:** [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt)
**Línea:** 2096

```kotlin
val sessionEnded = identityChanged || isRealLoop || (isManualRewind && session?.isProvisional == false)
```

**Análisis de componentes:**
1. `identityChanged`: Verdadero si el título o artista cambió.
2. `isRealLoop`: Verdadero si la canción volvió al inicio (>0.95 progreso -> <2000ms).
3. `isManualRewind`: Verdadero si hubo un salto atrás > 2000ms sin cambio de identidad, **siempre que la sesión no sea provisional**.

---

## AV2. ¿Un rebobinado dispara `[SKIP_MATH]` para la escucha anterior?

**Confirmación:** SÍ.

Cuando ocurre un rebobinado manual (`isManualRewind = true` e `identityChanged = false`), la variable `sessionEnded` se evalúa como `true` (en sesiones no provisionales). Esto desencadena el siguiente flujo:

1. **Envío al Canal:** Se envía un `HistoryEvent.CommitSession` al `historyChannel` (líneas 2156-2162).
2. **Consumo del Evento:** El loop consumidor en `serviceScope` recibe el evento y llama a `commitToHistory` (línea 754).
3. **Ejecución de Skip Math:** Dentro de `commitToHistory`, se ejecuta el bloque de lógica de skip (líneas 840-850):

```kotlin
// v9.0: Usar marca de agua rehidratada/persistente para clasificación (Bloque D)
val finalPos = maxPositionMs

// ... (Cálculo de effectiveDuration) ...

val progressFactor = if (effectiveDuration > 0) {
    finalPos.toFloat() / effectiveDuration.toFloat()
} else -1f

// FÓRMULA DE SKIP PURA (v6.5)
var isSkipped = progressFactor in 0.0f..0.4f

InternalLogger.d(applicationContext, "[DIAG_V6] [SKIP_MATH] Track=${endSnapshot.title}, FinalPos=${finalPos}ms, Duration=${effectiveDuration}ms, Factor=$progressFactor, Verdict=$isSkipped")
```

**Conclusión:** Un rebobinado manual se trata exactamente como un cierre de sesión por cambio de canción a efectos de historial. La sesión "pre-rebobinado" se archiva y se le aplica la matemática de skip basándose en el punto máximo que alcanzó (`maxPositionMs`) antes de que el usuario retrocediera el tiempo. Inmediatamente después, se crea una nueva `LogicalSession` (líneas 2182-2193) para la nueva escucha que comienza desde la posición rebobinada.

---
*Fin del reporte de evidencia.*
