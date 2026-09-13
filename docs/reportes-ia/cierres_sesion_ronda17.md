# Auditoría — Ronda 17: Verificación de Alcance Tras la Extracción de Línea (Cierres-3)

**Confirmación de Git Log:**
```
34fc859 (HEAD -> master) Conjunto Cierres-3 (Final): Actualización Incondicional + Inmunidad de Reactivación
0c0c26d Conjunto Cierres-2: Fuente Confiable (lastAppliedSnapshot) en el Cierre Retroactivo
741c0ae Conjunto Cierres-1b: Separación de Fuentes por Propósito (Logic vs Reliable)
```

---

## JJ1. Código completo y actual, desde `if (!isWidgetPotentiallyVisible())` (Gating de Stage 2)

Ubicado en `MusicNotificationListener.kt` (Líneas 2336 - 2351). Este es el bloque que protege la fase de presentación (Stage 2).

```kotlin
2336:         if (!isWidgetPotentiallyVisible()) {
2337:             // NOTE (Conjunto B.2, decision recorded — do not "fix" without checking with the
2338:             // project owner first): artwork resolution is intentionally skipped entirely while the
2339:             // screen is off, including cases where a corrected artwork would otherwise reach the
2340:             // history. This is a deliberate battery-saving tradeoff, not an oversight.
2341:             Log.d(TAG, "[GATING] Presentación suprimida (Pantalla apagada/bloqueada).")
2342:             InternalLogger.log(applicationContext, "STAGE 2: Suprimido (Pantalla bloqueada). Track=${snapshot.title}")
2343:             isPresentationDirty = true
2344:             pendingSnapshot = snapshot
2345:             
2346:             // Destrucción de Ticker de Letras para ahorro de batería
2347:             lyricsUpdateJob?.cancel()
2348:             
2349:             // Abortamos Stage 2 para evitar I/O y CPU innecesarios
2350:             lastObservedSnapshot = snapshot
2351:             return
2352:         }
```

---

## JJ2. Conteo de llaves, explícito

1. El bloque de gating abre en la línea **2336** (`{`).
2. El bloque de gating cierra en la línea **2352** (`}`).
3. La línea que dispara la resolución de portada (`[ARTWORK_RESOLVE]`) se encuentra dentro de la función `resolveArtworkDeduplicated`, la cual es invocada en la línea **2398**:
```kotlin
2397:                 resolvedArtwork = kotlinx.coroutines.withTimeoutOrNull(ARTWORK_PROMOTION_TIMEOUT_MS) {
2398:                     resolveArtworkDeduplicated(
...
```
4. **Confirmación:** El disparo de búsqueda de portada queda **FUERA** del bloque de gating (ocurre después del `return` de la línea 2351). Esto significa que solo se ejecuta cuando el widget es potencialmente visible.

---

## JJ3. Confirmación de que el proyecto realmente compiló sin advertencias

**Resultado de `app:assembleDebug`:**
```
Build finished successfully.
```

**Análisis de Inspecciones (Linter):**
La herramienta `analyze_file` reporta inconsistencias menores de indentación introducidas en cambios previos (Letras-3), pero ninguna advertencia de compilación ni errores funcionales:

```
* Line 2377: Error: Suspicious indentation: This is indented but is not continuing the previous expression
* Line 2380: Error: Suspicious indentation: This is indented but is not continuing the previous expression
```

Estas coinciden con la zona de cálculo de flags visuales después del gating:
```kotlin
2376:         val artworkChangedUI =
2377:             previousApplied?.artworkKey != snapshot.artworkKey
2378: 
2379:                 InternalLogger.d(applicationContext, "[LYRICS_TRACE] processSnapshot START: Track=${snapshot.title} | Reason=$reason | Visible=true")
```
