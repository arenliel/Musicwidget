# EXPEDIENTE DE ANOMALÍAS Y HALLAZGOS DE CAMPO — 28-08

Este documento recopila de forma rigurosa y acumulativa los comportamientos anómalos detectados en las distintas sesiones de prueba del día de hoy. Se mantiene la separación técnica por sesión para evitar confusiones de datos.

---

## SESIÓN 1 — RANGO [12:00:35 - 13:26:24]

### 1.1 ANOMALÍA: Pérdida de Artwork y Redundancia (Killing Time)
**Comportamiento reportado:** La canción aparece en "Now Playing" con arte, pero se archiva sin él. Además, hay duplicidad visual entre el widget y el historial.

**Verbatim de Logs:**
- **Detección de Arte:**
  `[13:21:09] DEBUG: [ART_LIFECYCLE] Fase A: Bitmap clonado en RAM y Disco para Killing Time`
  `[13:21:09] DEBUG: [ARTWORK_RESOLVE] Cache HIT en RAM. Key=content://com.spotify.mobile.android.mediaapi/spotify%3Aimage%3Aab67616d0000b2737339f7e95927d4b823189f62?transformation=NONE`
- **Consolidación:**
  `[13:21:20] DEBUG: [SHADOW_OBSERVER] Portada consolidada para: Killing Time|Magdalena Bay|234000 (from UUID 63283a18-b5ed-45fd-a402-b46898764f88)`
- **Redundancia:**
  `[13:21:20] INFO : LRU: Repetición detectada. Moviendo a la cima: Killing Time`

### 1.2 COMPORTAMIENTO: Paquetes Degradados (Pixel Player - Lilium)
**Comportamiento reportado:** Pixel Player envía metadatos que bloquean la UI.

**Verbatim de Logs:**
`[13:08:49] DEBUG: [DIAG_V5] [INTAKE] Recibido: Estado=OTHER(7), Track=Lilium..., Duración=-1ms, Reason=catch_up_render`
`[13:08:49] WARN : [DIAG_V5] [GATEKEEPER] Abortando flujo. Razón: Paquete degradado. Album=ángel, Duración=-1`

---

## SESIÓN 2 — RANGO [13:59:42 - 14:16:51]

### 2.1 ANOMALÍA: Desincronía de Hash en Artwork (Belly Breathing)
**Comportamiento reportado:** La canción se guarda sin portada a pesar de existir en la sesión viva.

**Verbatim de Logs:**
- **Escritura inicial:**
  `[14:14:10] DEBUG: [HISTORY_ART] Guardando imagen de historial en disco para: com.spotify.music|belly breathing|... -> Path: /data/user/0/arenliel.musicwidget/files/history/art_-1651305466.webp`
- **Renderizado (Mismatch):**
  `[14:16:17] DEBUG: [GLANCE_RENDER] Dibujando ítem de historial: Title = belly breathing | State = FILE_READY | Uri = file:///.../art_-610788208.webp`

### 2.2 COMPORTAMIENTO: Latencia Visual por Rate-Limit (Belly Breathing / Focus)
**Comportamiento reportado:** Las canciones no aparecen en el historial hasta que entra la siguiente pista.

**Verbatim de Logs:**
`[14:14:40] DEBUG: [HIST_CHANNEL] EVENT_SENT: Success=true, Track=belly breathing`
`[14:15:11] DEBUG: [HIST_CHANNEL] EVENT_SENT: Success=true, Failure=false, Closed=false, Track=focus`
`[14:15:12] WARN : GlanceAppWidgetManager W setWidgetPreview call for ComponentInfo{...MusicWidgetFullReceiver} with categories 7 was rate-limited`

### 2.3 COMPORTAMIENTO: Sincronía de Archivación (Image)
**Comportamiento reportado:** Percepción de retraso en la persistencia.

**Verbatim de Logs:**
`[13:21:37] DEBUG: [FSM] Nueva Sesión Creada (UUID=0e7aae0d...): Image`
`[13:22:19] DEBUG: [HIST_CONSUMER] EVENT_RECEIVED: Image (UUID=0e7aae0d..., Observado=42267ms)`
`[13:22:19] DEBUG: [HIST_CONSUMER] EVENT_PERSISTED: Image`

### 2.4 COMPORTAMIENTO: Fallo de Artwork Promotion (Pixel Player)
**Comportamiento reportado:** Portadas fallidas en Pixel Player.

**Verbatim de Logs:**
`[14:12:50] WARN : MusicListener W [ATOMIC] Artwork promotion TIMEOUT (3.5s). Forzando UI con placeholder.`

---

## DECLARACIÓN DE HALLAZGOS TÉCNICOS:
1. Se confirma que el sistema de logs `InternalLogger` ahora soporta **1000 líneas**, permitiendo capturar ambas sesiones sin sobrescritura.
2. Existe un patrón de **mismatch de hashes** (`art_XXXX`) entre lo que el `HistoryWorker` guarda y lo que Glance intenta renderizar, afectando a Spotify tras cambios de reproductor.
3. El sistema Android está aplicando **Rate-Limiting** a las actualizaciones de Glance, lo que genera una latencia percibida por el usuario que no es atribuible a la lógica del historial.

> **Fin del Expediente Acumulativo.**
