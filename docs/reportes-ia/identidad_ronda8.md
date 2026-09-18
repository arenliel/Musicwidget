# Auditoría — Ronda 8 (Identidad): ¿Un Rebobinado Cierra la Escucha, o Solo la Identidad Real lo Hace?

## Evidencia de Git
**Comando:** `git log --oneline -3`
```text
3e85a7a (HEAD -> master) Conjunto Artwork-Final: Reemplazar previousApplied por previousLogical para sanear la detección de cambios
dcb4f54 Conjunto Artwork-Trace: Instrumentación de sincronía de archivos de portada
699229c Conjunto Artwork-Confirm: Registro de confirmación de persistencia real
```

---

## AW1. Fórmula completa y exacta de `sessionEnded`

**Archivo:** [MusicNotificationListener.kt](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt)
**Línea:** 2096

```kotlin
val sessionEnded = identityChanged || isRealLoop || (isManualRewind && session?.isProvisional == false)
```

**Confirmación:** La fórmula **SÍ** incluye `isManualRewind` (condicionado a que la sesión no sea provisional). Por lo tanto, un rebobinado manual **cierra** la escucha actual.

---

## AW2. ¿Qué posición usa `[SKIP_MATH]` al cerrar por rebobinado — la actual, o `maxPositionMs`?

**Confirmación:** Usa la **marca de agua** (`maxPositionMs`), no la posición degradada del rebobinado.

**Evidencia (Emisor):** [Línea 2146-2152](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt#L2146-L2152)
```kotlin
val commitEvent = HistoryEvent.CommitSession(
    // ...
    maxPositionMs = max(session.maxPositionMs, session.liveSnapshot.projectedPositionMs()),
    // ...
)
```

**Evidencia (Consumidor):** [Línea 840-848](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt#L840-L848)
```kotlin
// v9.0: Usar marca de agua rehidratada/persistente para clasificación (Bloque D)
val finalPos = maxPositionMs
// ...
val progressFactor = if (effectiveDuration > 0) {
    finalPos.toFloat() / effectiveDuration.toFloat()
} else -1f
// ...
InternalLogger.d(applicationContext, "[DIAG_V6] [SKIP_MATH] Track=${endSnapshot.title}, FinalPos=${finalPos}ms, ...")
```

---

## AW3. ¿Rebobinar varias veces seguidas genera varias entradas de historial?

**Confirmación:** **SÍ**. Genera múltiples sesiones independientes.

1. **Instanciación:** Cada vez que `sessionEnded` es verdadero, se crea una nueva `LogicalSession` ([Línea 2182](file:///C:/Users/arenliel/AndroidStudioProjects/MusicWidget/app/src/main/java/com/example/musicwidget/MusicNotificationListener.kt#L2182)).
2. **UUID Único:** La clase `LogicalSession` genera un nuevo UUID aleatorio por defecto en cada creación:
   ```kotlin
   private data class LogicalSession(
       val sessionUUID: String = java.util.UUID.randomUUID().toString(),
       // ...
   )
   ```
3. **Persistencia:** Si cada fragmento de escucha resultante del rebobinado cumple con los criterios mínimos de duración del consumidor (ej. `durationObserved >= 5000L` en línea 754), cada uno se persistirá como una entrada separada en el historial. No hay mecanismo de "agrupación" o "fusión" para rebobinados sucesivos; son tratados como eventos discretos.

---
*Fin del reporte de evidencia.*
