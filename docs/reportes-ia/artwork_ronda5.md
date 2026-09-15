# Auditoría — Ronda 5 (Portadas): ¿`artIncoherent` Cubre los Mismos Casos que la Condición Original?

**Confirmación de Git Log:**
```
e54d9d0 (HEAD -> master) Conjunto Artwork-1: Conservar Portada y Evitar Resolución Innecesaria (artIncoherent based)
998c2ef Conjunto Cierres-4: Corregir la Fuente de previousApplied (lastAppliedSnapshot -> lastLogicalSnapshot)
34fc859 Conjunto Cierres-3 (Final): Actualización Incondicional + Inmunidad de Reactivación
```

---

## YY1. Declaración completa y actual de `artIncoherent`

Ubicada en `MusicNotificationListener.kt` (Línea 1991):

```kotlin
1991:         val artIncoherent = savedArtworkKey != rawSnapshot.artworkKey && isWidgetPotentiallyVisible()
```

---

## YY2. Declaración completa de `trackChangedUI`, `artworkChangedUI`, y `savedArtworkKey`

Ubicadas en `MusicNotificationListener.kt`.

### `trackChangedUI` (Líneas 2361-2362):
```kotlin
2361:         val trackChangedUI = 
2362:             previousApplied?.trackKey != snapshot.trackKey
```

### `artworkChangedUI` (Líneas 2377-2378):
```kotlin
2377:         val artworkChangedUI =
2378:             previousApplied?.artworkKey != snapshot.artworkKey
```

### `savedArtworkKey`:
Es una propiedad de la clase `MusicNotificationListener` (Línea 347):
```kotlin
347:     private var savedArtworkKey: String? = null
```

---

## YY3. Lectura directa del estado actual del código (no narrativa)

(Líneas 2380 - 2405 de `MusicNotificationListener.kt`):

```kotlin
2380:                 InternalLogger.d(applicationContext, "[LYRICS_TRACE] processSnapshot START: Track=${snapshot.title} | Reason=$reason | Visible=true")
2381: 
2382:         try {
2383: 
2384:             // 1. Resolución de recursos visuales (Fase Cancelable).
2385:             var resolvedArtwork: Bitmap? = null
2386:             var resolvedAppIconFinal: Bitmap? = null
2387:             var resolvedIconKey: String? = null
2388:             var resolvedTierFinal: Int = TIER_NONE
2389: 
2390:             // Hallazgo 1.1: Fail-safe Atomic Promotion (v3.1)
2391:             // Watchdog de 3.5s para no bloquear la UI si la red es lenta.
2392:             var artworkTimedOut = false
2393: 
2394:             // REGLA Artwork-1: Resolución dirigida por incoherencia (evita ráfagas CPU)
2395:             if (controller != null && metadata != null && artIncoherent) {
2396:                 
2397:                 // A. Portada (v6.3 Pipeline Unificado)
2398:                 resolvedArtwork = kotlinx.coroutines.withTimeoutOrNull(ARTWORK_PROMOTION_TIMEOUT_MS) {
2399:                     resolveArtworkDeduplicated(
2400:                         snapshot = snapshot,
2401:                         controller = controller,
2402:                         metadata = metadata,
2403:                         generation = myGeneration
2404:                     )
2405:                 } ?: run {
```

---

## YY4. Confirmación real del commit

```
commit e54d9d0aabcc5942f0c60a88bafcefb7129bd15c (HEAD -> master)
Author: arenliel <alvz.angel12@gmail.com>
Date:   Mon Sep 14 00:34:33 2026 -0400

    Conjunto Artwork-1: Conservar Portada y Evitar Resolución Innecesaria (artIncoherent based)

 .../musicwidget/MusicNotificationListener.kt       |  36 ++-
 docs/reportes-ia/artwork_ronda1.md                 | 285 ++++++++++++++++++++
 docs/reportes-ia/artwork_ronda2.md                 |  84 ++++++
 docs/reportes-ia/artwork_ronda3.md                 |  58 ++++
 docs/reportes-ia/artwork_ronda4.md                 |  36 +++
 docs/reportes-ia/cierres_sesion_ronda21.md         |  33 +++
 docs/reportes-ia/cierres_sesion_ronda22.md         | 299 +++++++++++++++++++++
 docs/reportes-ia/cierres_sesion_ronda23.md         | 114 ++++++++
 docs/reportes-ia/cierres_sesion_ronda24.md         |  77 ++++++
 docs/reportes-ia/cierres_sesion_ronda25.md         |  79 ++++++
 10 files changed, 1087 insertions(+), 14 deletions(-)
```
