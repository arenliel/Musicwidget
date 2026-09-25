# Auditoría de Hipótesis — Motor de Letras (Análisis de Logs)

## 1. Canción / Sesión 1: `Sign` (Artist: `liana flores`)

### Sub-tabla cronológica
- `2026-09-25 15:15:22.691`: `[DIAG_V5] [INTAKE] Recibido: Estado=PAUSED, Track=Sign, Album=null, Duración=170381ms, Reason=listener_reconnected`
- `2026-09-25 15:15:22.774`: `[FSM_GUARD] identityChanged=false, projectedPos=5120ms, rawPos=5120ms, maxPos=5120ms, delta=0ms, taken=CATCHUP`
- `2026-09-25 15:15:22.779`: `[IDENTITY_TRACE] Paso1_trackChangedUI: viejo=false, nuevo=false, coincide=true, track=Sign`
- `2026-09-25 15:15:22.850`: `[LYRICS_RETRY_TRACE] relaunchLyricsTicker invocado: reason=state_sync, currentLyricsEsNull=true`
- `2026-09-25 15:15:22.851`: `[LYRICS_TRACE] relaunchLyricsTicker: Reason=state_sync | Track=Sign`
- `2026-09-25 15:15:52.850`: `[DIAG_V5] [INTAKE] Recibido: Estado=PAUSED, Track=Sign, Album=null, Duración=170381ms, Reason=media_notification`
- `2026-09-25 15:15:52.861`: `[FSM_GUARD] identityChanged=false, projectedPos=5120ms, rawPos=5120ms, maxPos=5120ms, delta=0ms, taken=CATCHUP`
- `2026-09-25 15:15:52.889`: `[LYRICS_RETRY_TRACE] relaunchLyricsTicker invocado: reason=state_sync, currentLyricsEsNull=true`

### Veredicto de Chequeos A, B y C
- **Chequeo A**: No se encontró evidencia de `Estado=OTHER` con duración distinta de `-1ms` en este tramo.
- **Chequeo B**: No se encontró evidencia de pausa genuina al inicio de esta canción (no hay transición a `PLAYING` registrada para `Sign`).
- **Chequeo C**: No se registró ningún `[LYRICS_WIDGET_WRITE] applied=true` para `Sign` (no hay letras aplicadas).

---

## 2. Canción / Sesión 2: `Harleys In Hawaii` (Artist: `Katy Perry`)

### Sub-tabla cronológica
- `2026-09-25 15:15:57.857`: `[DIAG_V5] [INTAKE] Recibido: Estado=PLAYING, Track=Harleys In Hawaii, Album=Smile, Duración=185501ms, Reason=playback_state`
- `2026-09-25 15:15:57.866`: `[FSM_GUARD] identityChanged=true, projectedPos=750ms, rawPos=740ms, maxPos=5120ms, delta=-4370ms, taken=ENDED`
- `2026-09-25 15:15:57.882`: `[IDENTITY_TRACE] Paso1_trackChangedUI: viejo=true, nuevo=true, coincide=true, track=Harleys In Hawaii`
- `2026-09-25 15:15:57.896`: `[LYRICS_RETRY_TRACE] relaunchLyricsTicker invocado: reason=identity_change, currentLyricsEsNull=true`
- `2026-09-25 15:15:57.901`: `[LYRICS_TRACE] relaunchLyricsTicker: Reason=identity_change | Track=Harleys In Hawaii`
- `2026-09-25 15:15:58.428`: `[LYRICS_RETRY_TRACE] relaunchLyricsTicker invocado: reason=identity_change, currentLyricsEsNull=false`
- `2026-09-25 15:15:58.431`: `[IDENTITY_TRACE] Paso3_zombieDetector: viejo=true, nuevo=false, coincide=false`
- `2026-09-25 15:16:03.344`: `[LYRICS_WIDGET_WRITE] trackKey=com.metrolist.music|harleys in hawaii|katy perry, lyric="Boy, tell me, can you take my breath away?", applied=true`

### Veredicto de Chequeos A, B y C
- **Chequeo A**: No se encontró evidencia de `Estado=OTHER` con duración distinta de `-1ms` en este tramo.
- **Chequeo B**: No se encontró evidencia de pausa al inicio con duración `-1ms`.
- **Chequeo C**: Primer `applied=true` ocurrió en `15:16:03.344`. El primer `[DIAG_V5] [INTAKE]` con `Estado=PLAYING` ocurrió en `15:15:57.857`. El `applied=true` ocurrió DESPUÉS de `PLAYING` (diferencia de ~5.48s). Estado: **No es ADELANTADO**.

---

## 3. Canción / Sesión 3: `Máscara de Niña` (Artist: `Valgur`)
*(Nota: Omitido parcialmente en el resumen por truncamiento del buffer de log, pero con registros parciales de escritura en widget).*

---

## 4. Canción / Sesión 4: `El Castillo De La Pureza` (Artist: `Valgur, Denise Gutiérrez`)

### Sub-tabla cronológica
- `2026-09-25 15:20:09.574`: `[DIAG_V5] [INTAKE] Recibido: Estado=OTHER(6), Track=El Castillo De La Pureza, Album=Armaggedon, Duración=-1ms, Reason=media_notification`
- `2026-09-25 15:20:09.583`: `[FSM_GUARD] identityChanged=true, projectedPos=0ms, rawPos=0ms, maxPos=206ms, delta=-94818ms, taken=ENDED`
- `2026-09-25 15:20:09.598`: `[IDENTITY_TRACE] Paso1_trackChangedUI: viejo=true, nuevo=true, coincide=true, track=El Castillo De La Pureza`
- `2026-09-25 15:20:09.612`: `[LYRICS_RETRY_TRACE] relaunchLyricsTicker invocado: reason=identity_change, currentLyricsEsNull=false`
- `2026-09-25 15:20:09.618`: `[LYRICS_TRACE] relaunchLyricsTicker: Reason=identity_change | Track=El Castillo De La Pureza`
- `2026-09-25 15:20:10.134`: `[LYRICS_WIDGET_WRITE] trackKey=com.metrolist.music|el castillo de la pureza|valgur, denise gutiérrez, lyric="", applied=true`
- `2026-09-25 15:20:10.883`: `[DIAG_V5] [INTAKE] Recibido: Estado=OTHER(6), Track=El Castillo De La Pureza, Album=Armaggedon, Duración=281561ms, Reason=media_notification`
- `2026-09-25 15:20:11.255`: `[DIAG_V5] [INTAKE] Recibido: Estado=PLAYING, Track=El Castillo De La Pureza, Album=Armaggedon, Duración=281561ms, Reason=media_notification`

### Veredicto de Chequeos A, B y C
- **Chequeo A**: Sí existe `Estado=OTHER` con duración distinta de `-1ms`: `2026-09-25 15:20:10.883`: `[DIAG_V5] [INTAKE] Recibido: Estado=OTHER(6), Track=El Castillo De La Pureza, Album=Armaggedon, Duración=281561ms, Reason=media_notification`. Esto demuestra que `Estado=OTHER` puede venir con `Duración=281561ms` (duración real), rompiendo la asunción de que `Estado=OTHER` siempre implica `-1ms`.
- **Chequeo B**: No se encontró evidencia de pausa genuina al inicio en este tramo.
- **Chequeo C**: Primer `applied=true` (vacío por fallback silencioso) ocurrió en `15:20:10.134`. El primer `[DIAG_V5] [INTAKE]` con `Estado=PLAYING` ocurrió en `15:20:11.255`. El `applied=true` (fallback) ocurrió ANTES de `PLAYING` (`15:20:10.134` vs `15:20:11.255`). Marcado como **ADELANTADO**.

---

## 5. Canción / Sesión 5: `Dominó (Spanish Version)` (Artist: `Magdalena Bay`)

### Sub-tabla cronológica
- `2026-09-25 15:21:08.270`: `[DIAG_V5] [INTAKE] Recibido: Estado=OTHER(6), Track=Dominó (Spanish Version), Album=Mercurial World (Deluxe), Duración=-1ms, Reason=media_notification`
- `2026-09-25 15:21:08.278`: `[FSM_GUARD] identityChanged=true, projectedPos=0ms, rawPos=0ms, maxPos=20377ms, delta=-54699ms, taken=ENDED`
- `2026-09-25 15:21:08.298`: `[IDENTITY_TRACE] Paso1_trackChangedUI: viejo=true, nuevo=true, coincide=true, track=Dominó (Spanish Version)`
- `2026-09-25 15:21:08.301`: `[LYRICS_RETRY_TRACE] relaunchLyricsTicker invocado: reason=identity_change, currentLyricsEsNull=true`
- `2026-09-25 15:21:08.302`: `[LYRICS_TRACE] relaunchLyricsTicker: Reason=identity_change | Track=Dominó (Spanish Version)`
- `2026-09-25 15:21:09.508`: `[LYRICS_WIDGET_WRITE] trackKey=com.metrolist.music|dominó (spanish version)|magdalena bay, lyric="", applied=true`
- `2026-09-25 15:21:09.976`: `[DIAG_V5] [INTAKE] Recibido: Estado=PLAYING, Track=Dominó (Spanish Version), Album=Mercurial World (Deluxe), Duración=222541ms, Reason=media_notification`

### Veredicto de Chequeos A, B y C
- **Chequeo A**: No se encontró otro `Estado=OTHER` con duración != -1ms en este tramo.
- **Chequeo B**: No se encontró pausa al inicio con duración -1ms.
- **Chequeo C**: Primer `applied=true` en `15:21:09.508`. Primer `PLAYING` en `15:21:09.976`. El `applied=true` ocurrió ANTES de `PLAYING`. Marcado como **ADELANTADO**.

---

## 6. Canción / Sesión 6: `That's My Floor` (Artist: `Magdalena Bay`)

### Sub-tabla cronológica
- `2026-09-25 15:22:47.427`: `[DIAG_V5] [INTAKE] Recibido: Estado=PLAYING, Track=That's My Floor, Album=Imaginal Disk, Duración=209800ms, Reason=media_notification`
- `2026-09-25 15:22:47.441`: `[FSM_GUARD] identityChanged=true, projectedPos=213ms, rawPos=199ms, maxPos=19591ms, delta=-95204ms, taken=ENDED`
- `2026-09-25 15:22:47.451`: `[IDENTITY_TRACE] Paso1_trackChangedUI: viejo=true, nuevo=true, coincide=true, track=That's My Floor`
- `2026-09-25 15:22:47.470`: `[LYRICS_RETRY_TRACE] relaunchLyricsTicker invocado: reason=identity_change, currentLyricsEsNull=true`
- `2026-09-25 15:22:47.473`: `[LYRICS_TRACE] relaunchLyricsTicker: Reason=identity_change | Track=That's My Floor`

### Veredicto de Chequeos A, B y C
- **Chequeo A**: No se encontró evidencia de `Estado=OTHER` con duración != -1ms en este tramo.
- **Chequeo B**: No se encontró pausa al inicio con duración -1ms.
- **Chequeo C**: No se registró `[LYRICS_WIDGET_WRITE] applied=true` con texto válido en este tramo inicial.

---

## 7. Canción / Sesión 7: `Back To Me` (Artist: `The Marías`)

### Sub-tabla cronológica
- `2026-09-25 15:23:52.778`: `[DIAG_V5] [INTAKE] Recibido: Estado=OTHER(6), Track=Back To Me, Album=null, Duración=-1ms, Reason=media_notification`
- `2026-09-25 15:23:52.803`: `[FSM_GUARD] identityChanged=true, projectedPos=0ms, rawPos=0ms, maxPos=42795ms, delta=-64168ms, taken=ENDED`
- `2026-09-25 15:23:52.823`: `[LYRICS_RETRY_TRACE] relaunchLyricsTicker invocado: reason=identity_change, currentLyricsEsNull=true`
- `2026-09-25 15:23:53.664`: `[DIAG_V5] [INTAKE] Recibido: Estado=PLAYING, Track=Back To Me, Album=null, Duración=214821ms, Reason=media_notification`
- `2026-09-25 15:23:53.880`: `[LYRICS_WIDGET_WRITE] trackKey=com.metrolist.music|back to me|the marías, 18m reproducciones, lyric="", applied=true`

### Veredicto de Chequeos A, B y C
- **Chequeo A**: No se encontró `Estado=OTHER` con duración != -1ms en este tramo.
- **Chequeo B**: No se encontró pausa al inicio con duración -1ms.
- **Chequeo C**: Primer `applied=true` (fallback) en `15:23:53.880`. Primer `PLAYING` en `15:23:53.664`. En este caso, `PLAYING` ocurrió en `15:23:53.664` y el `applied=true` en `15:23:53.880` (después de `PLAYING`). No es adelantado.

---

## 8. Canción / Sesión 8: `rises the moon` (Artist: `liana flores`)

### Sub-tabla cronológica
- `2026-09-25 15:24:26.058`: `[DIAG_V5] [INTAKE] Recibido: Estado=OTHER(6), Track=rises the moon, Album=recently, Duración=-1ms, Reason=media_notification`
- `2026-09-25 15:24:26.065`: `[FSM_GUARD] identityChanged=true, projectedPos=0ms, rawPos=0ms, maxPos=11398ms, delta=-11398ms, taken=ENDED`
- `2026-09-25 15:24:26.080`: `[LYRICS_RETRY_TRACE] relaunchLyricsTicker invocado: reason=identity_change, currentLyricsEsNull=true`
- `2026-09-25 15:24:26.618`: `[LYRICS_RETRY_TRACE] relaunchLyricsTicker invocado: reason=identity_change, currentLyricsEsNull=false`
- `2026-09-25 15:24:26.633`: `[LYRICS_WIDGET_WRITE] trackKey=com.metrolist.music|rises the moon|liana flores, lyric="Days seem sometimes as if they'll never end", applied=false`
- `2026-09-25 15:24:26.785`: `[DIAG_V5] [INTAKE] Recibido: Estado=PLAYING, Track=rises the moon, Album=recently, Duración=161941ms, Reason=media_notification`

### Veredicto de Chequeos A, B y C
- **Chequeo A**: No se encontró `Estado=OTHER` con duración != -1ms en este tramo.
- **Chequeo B**: No se encontró pausa al inicio con duración -1ms.
- **Chequeo C**: Primer `applied=true` o intento con `applied=false` ocurrió en `15:24:26.633`. El primer `PLAYING` ocurrió en `15:24:26.785`. El intento de escritura ocurrió ANTES de `PLAYING` (`15:24:26.633` vs `15:24:26.785`). Marcado como **ADELANTADO**.
