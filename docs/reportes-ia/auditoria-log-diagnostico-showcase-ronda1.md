# EXTRACCIÓN DE EVIDENCIA — Log de reproducción con diagnóstico aplicado (RONDA 1)

## Paso 0 — Confirmación de HEAD actual
```text
fdd6e89c7f999bf9abd3756970fa7c36df8ba4cd
```

## Paso 1 — Confirmación de que las líneas de diagnóstico existen en el código actual
```text
app\src\main\java\com\example\musicwidget\MusicNotificationListener.kt:2875:                InternalLogger.d(applicationContext, "[TICKER_DEBUG] reason=$reason, targetIdentity=$targetIdentity, canReuse=$canReuse, currentInfo.isPlaying=${currentInfo.isPlaying}")
app\src\main\java\com\example\musicwidget\MusicNotificationListener.kt:2905:            InternalLogger.d(applicationContext, "[SHOWCASE_DEBUG] myTrackKey=$myTrackKey, currentPos=$currentPos, positionMs=${snapshot.positionMs}, positionUpdatedAtRealtime=${snapshot.positionUpdatedAtRealtime}, playbackState=${snapshot.playbackState}, entryTs=${entry?.timestampMs}, firstEntryTs=${lyricsRes.allEntries.firstOrNull()?.timestampMs}")
app\src\main\java\com\example\musicwidget/MusicNotificationListener.kt:2974:            InternalLogger.d(applicationContext, "[PAUSEDCYCLE_DEBUG] myTrackKey=$myTrackKey, pausedPos=$pausedPos, fallbackFired=$fallbackFired, hasConfirmedPlayback=${currentLogicalSession?.hasConfirmedPlayback}, lastEntryTs=${lastEntry?.timestampMs}")
```

## Paso 3 — Extracción de líneas verbatim del log capturado (`C:\Users\arenliel\Desktop\logs-fdd6e89.txt`)

### 1. `TICKER_DEBUG`
```text
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:100:2026-09-25 19:33:57.276  3998-4146  InternalLogger          arenliel.musicwidget                 D
[TICKER_DEBUG] reason=state_sync, targetIdentity=com.metrolist.music|rosa pastel|belanova, canReuse=false, currentInfo.isPlaying=false
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:130:2026-09-25 19:34:09.285  3998-4038  InternalLogger          arenliel.musicwidget                 D
[TICKER_DEBUG] reason=state_sync, targetIdentity=com.metrolist.music|rosa pastel|belanova, canReuse=true, currentInfo.isPlaying=true
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:137:2026-09-25 19:34:09.288  3998-4045  InternalLogger          arenliel.musicwidget                 D
[TICKER_DEBUG] reason=state_sync, targetIdentity=com.metrolist.music|rosa pastel|belanova, canReuse=true, currentInfo.isPlaying=true
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:446:2026-09-25 19:34:43.101  3998-4043  InternalLogger          arenliel.musicwidget                 D
[TICKER_DEBUG] reason=identity_change, targetIdentity=com.metrolist.music|turning off the rain|magdalena bay, canReuse=true, currentInfo.isPlaying=false
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:472:2026-09-25 19:34:43.816  3998-4936  InternalLogger          arenliel.musicwidget                 D
[TICKER_DEBUG] reason=state_sync, targetIdentity=com.metrolist.music|turning off the rain|magdalena bay, canReuse=true, currentInfo.isPlaying=false
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:476:2026-09-25 19:34:43.822  3998-4054  InternalLogger          arenliel.musicwidget                 D
[TICKER_DEBUG] reason=state_sync, targetIdentity=com.metrolist.music|turning off the rain|magdalena bay, canReuse=true, currentInfo.isPlaying=true
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:1215:2026-09-25 19:36:50.557  3998-5472  InternalLogger          arenliel.musicwidget                 D
[TICKER_DEBUG] reason=identity_change, targetIdentity=com.metrolist.music|cry for me|magdalena bay, canReuse=true, currentInfo.isPlaying=false
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:1236:2026-09-25 19:36:50.745  3998-4043  InternalLogger          arenliel.musicwidget                 D
[TICKER_DEBUG] reason=state_sync, targetIdentity=com.metrolist.music|cry for me|magdalena bay, canReuse=true, currentInfo.isPlaying=false
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:1245:2026-09-25 19:36:50.793  3998-4039  InternalLogger          arenliel.musicwidget                 D
[TICKER_DEBUG] reason=state_sync, targetIdentity=com.metrolist.music|cry for me|magdalena bay, canReuse=true, currentInfo.isPlaying=true
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:1628:2026-09-25 19:38:58.777  3998-4936  InternalLogger          arenliel.musicwidget                 D
[TICKER_DEBUG] reason=identity_change, targetIdentity=com.metrolist.music|see you again (con kali uchis)|tyler, the creator, canReuse=true, currentInfo.isPlaying=false
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:1669:2026-09-25 19:38:59.171  3998-4043  InternalLogger          arenliel.musicwidget                 D
[TICKER_DEBUG] reason=state_sync, targetIdentity=com.metrolist.music|see you again (con kali uchis)|tyler, the creator, canReuse=true, currentInfo.isPlaying=false
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:1671:2026-09-25 19:38:59.174  3998-4046  InternalLogger          arenliel.musicwidget                 D
[TICKER_DEBUG] reason=state_sync, targetIdentity=com.metrolist.music|see you again (con kali uchis)|tyler, the creator, canReuse=true, currentInfo.isPlaying=true
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:1909:2026-09-25 19:39:24.845  3998-4936  InternalLogger          arenliel.musicwidget                 D
[TICKER_DEBUG] reason=identity_change, targetIdentity=com.metrolist.music|good luck, babe!|chappell roan, canReuse=false, currentInfo.isPlaying=false
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:1981:2026-09-25 19:39:25.126  3998-7917  InternalLogger          arenliel.musicwidget                 D
[TICKER_DEBUG] reason=identity_change, targetIdentity=com.metrolist.music|good luck, babe!|chappell roan, canReuse=true, currentInfo.isPlaying=false
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:2000:2026-09-25 19:39:25.977  3998-4042  InternalLogger          arenliel.musicwidget                 D
[TICKER_DEBUG] reason=state_sync, targetIdentity=com.metrolist.music|good luck, babe!|chappell roan, canReuse=true, currentInfo.isPlaying=false
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:2011:2026-09-25 19:39:26.005  3998-4040  InternalLogger          arenliel.musicwidget                 D
[TICKER_DEBUG] reason=state_sync, targetIdentity=com.metrolist.music|good luck, babe!|chappell roan, canReuse=true, currentInfo.isPlaying=true
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:2293:2026-09-25 19:40:23.637  3998-4038  InternalLogger          arenliel.musicwidget                 D
[TICKER_DEBUG] reason=identity_change, targetIdentity=com.metrolist.music|froot|marina, canReuse=false, currentInfo.isPlaying=false
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:2333:2026-09-25 19:40:25.118  3998-4041  InternalLogger          arenliel.musicwidget                 D
[TICKER_DEBUG] reason=state_sync, targetIdentity=com.metrolist.music|froot|marina, canReuse=true, currentInfo.isPlaying=false
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:2349:2026-09-25 19:40:25.193  3998-5472  InternalLogger          arenliel.musicwidget                 D
[TICKER_DEBUG] reason=state_sync, targetIdentity=com.metrolist.music|froot|marina, canReuse=true, currentInfo.isPlaying=true
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:2599:2026-09-25 19:41:05.034  3998-4040  InternalLogger          arenliel.musicwidget                 D
[TICKER_DEBUG] reason=state_sync, targetIdentity=com.metrolist.music|froot|marina, canReuse=true, currentInfo.isPlaying=true
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:2606:2026-09-25 19:41:05.044  3998-4038  InternalLogger          arenliel.musicwidget                 D
[TICKER_DEBUG] reason=state_sync, targetIdentity=com.metrolist.music|froot|marina, canReuse=true, currentInfo.isPlaying=false
```

### 2. `SHOWCASE_DEBUG`
```text
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:135:2026-09-25 19:34:09.287  3998-4038  InternalLogger          arenliel.musicwidget                 D
[SHOWCASE_DEBUG] myTrackKey=com.metrolist.music|rosa pastel|belanova, currentPos=48918, positionMs=48868, positionUpdatedAtRealtime=1788105223,
playbackState=3, entryTs=47260, firstEntryTs=24370
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:141:2026-09-25 19:34:09.290  3998-4045  InternalLogger          arenliel.musicwidget                 D
[SHOWCASE_DEBUG] myTrackKey=com.metrolist.music|rosa pastel|belanova, currentPos=48921, positionMs=48868, positionUpdatedAtRealtime=1788105223,
playbackState=3, entryTs=47260, firstEntryTs=24370
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:158:2026-09-25 19:34:09.583  3998-4044  InternalLogger          arenliel.musicwidget                 D
[SHOWCASE_DEBUG] myTrackKey=com.metrolist.music|rosa pastel|belanova, currentPos=49214, positionMs=48868, positionUpdatedAtRealtime=1788105223,
playbackState=3, entryTs=49710, firstEntryTs=24370
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:173:2026-09-25 19:34:11.714  3998-4038  InternalLogger          arenliel.musicwidget                 D
[SHOWCASE_DEBUG] myTrackKey=com.metrolist.music|rosa pastel|belanova, currentPos=51345, positionMs=48868, positionUpdatedAtRealtime=1788105223,
playbackState=3, entryTs=51840, firstEntryTs=24370
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:187:2026-09-25 19:34:14.492  3998-4046  InternalLogger          arenliel.musicwidget                 D
[SHOWCASE_DEBUG] myTrackKey=com.metrolist.music|rosa pastel|belanova, currentPos=54123, positionMs=48868, positionUpdatedAtRealtime=1788105223,
playbackState=3, entryTs=54620, firstEntryTs=24370
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:205:2026-09-25 19:34:18.743  3998-4046  InternalLogger          arenliel.musicwidget                 D
[SHOWCASE_DEBUG] myTrackKey=com.metrolist.music|rosa pastel|belanova, currentPos=58374, positionMs=48868, positionUpdatedAtRealtime=1788105223,
playbackState=3, entryTs=58870, firstEntryTs=24370
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:220:2026-09-25 19:34:21.782  3998-4047  InternalLogger          arenliel.musicwidget                 D
[SHOWCASE_DEBUG] myTrackKey=com.metrolist.music|rosa pastel|belanova, currentPos=61413, positionMs=48868, positionUpdatedAtRealtime=1788105223,
playbackState=3, entryTs=61910, firstEntryTs=24370
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:235:2026-09-25 19:34:26.422  3998-4046  InternalLogger          arenliel.musicwidget                 D
[SHOWCASE_DEBUG] myTrackKey=com.metrolist.music|rosa pastel|belanova, currentPos=66054, positionMs=48868, positionUpdatedAtRealtime=1788105223,
playbackState=3, entryTs=66550, firstEntryTs=24370
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:251:2026-09-25 19:34:30.252  3998-4038  InternalLogger          arenliel.musicwidget                 D
[SHOWCASE_DEBUG] myTrackKey=com.metrolist.music|rosa pastel|belanova, currentPos=69883, positionMs=48868, positionUpdatedAtRealtime=1788105223,
playbackState=3, entryTs=70380, firstEntryTs=24370
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:265:2026-09-25 19:34:32.102  3998-4047  InternalLogger          arenliel.musicwidget                 D
[SHOWCASE_DEBUG] myTrackKey=com.metrolist.music|rosa pastel|belanova, currentPos=71733, positionMs=48868, positionUpdatedAtRealtime=1788105223,
playbackState=3, entryTs=72230, firstEntryTs=24370
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:280:2026-09-25 19:34:34.163  3998-4043  InternalLogger          arenliel.musicwidget                 D
[SHOWCASE_DEBUG] myTrackKey=com.metrolist.music|rosa pastel|belanova, currentPos=73794, positionMs=48868, positionUpdatedAtRealtime=1788105223,
playbackState=3, entryTs=74290, firstEntryTs=24370
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:295:2026-09-25 19:34:36.623  3998-4047  InternalLogger          arenliel.musicwidget                 D
[SHOWCASE_DEBUG] myTrackKey=com.metrolist.music|rosa pastel|belanova, currentPos=76254, positionMs=48868, positionUpdatedAtRealtime=1788105223,
playbackState=3, entryTs=76750, firstEntryTs=24370
```

### 3. `PAUSEDCYCLE_DEBUG`
*(Nota: No se registraron eventos `PAUSEDCYCLE_DEBUG` en este tramo de log debido a que la reproducción se mantuvo activa o no entró al ciclo de pausa bajo las condiciones evaluadas).*

### 4. `LYRICS_TRACE | LYRICS_RETRY_TRACE`
```text
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:72:2026-09-25 19:33:57.261  3998-4041  InternalLogger          arenliel.musicwidget                 D
[LYRICS_TRACE] processSnapshot START: Track=Rosa Pastel | Reason=listener_reconnected | Visible=true
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:98:2026-09-25 19:33:57.264  3998-4041  InternalLogger          arenliel.musicwidget                 D
[LYRICS_RETRY_TRACE] Entrando a sincronización pasiva: currentLyricsEsNull=true, trackKey=com.metrolist.music|rosa pastel|belanova|185941, durationMs=185941
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:99:2026-09-25 19:33:57.265  3998-4041  InternalLogger          arenliel.musicwidget                 D
[LYRICS_RETRY_TRACE] Pre-shouldResync: stateChangedUI=false, drift=0, tickerActivo=null, currentLyricsEsNull=true
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:101:2026-09-25 19:33:57.276  3998-4146  InternalLogger          arenliel.musicwidget                 D
[LYRICS_TRACE] relaunchLyricsTicker: Reason=state_sync | Track=Rosa Pastel
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:127:2026-09-25 19:34:09.283  3998-4038  InternalLogger          arenliel.musicwidget                 D
[LYRICS_TRACE] processSnapshot START: Track=Rosa Pastel | Reason=media_notification | Visible=true
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:128:2026-09-25 19:34:09.284  3998-4038  InternalLogger          arenliel.musicwidget                 D
[LYRICS_RETRY_TRACE] Entrando a sincronización pasiva: currentLyricsEsNull=false, trackKey=com.metrolist.music|rosa pastel|belanova|185941, durationMs=185941
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:129:2026-09-25 19:34:09.284  3998-4038  InternalLogger          arenliel.musicwidget                 D
[LYRICS_RETRY_TRACE] Pre-shouldResync: stateChangedUI=true, drift=11019, tickerActivo=false, currentLyricsEsNull=false
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:131:2026-09-25 19:34:09.286  3998-4038  InternalLogger          arenliel.musicwidget                 D
[LYRICS_TRACE] relaunchLyricsTicker: Reason=state_sync | Track=Rosa Pastel
```

### 5. `DIAG_V5` `[INTAKE]`
```text
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:17:2026-09-25 19:33:56.196  3998-4045  InternalLogger          arenliel.musicwidget                 D
[DIAG_V5] [INTAKE] Recibido: Estado=PAUSED, Track=Rosa Pastel, Album=null, Duración=185941ms, Reason=listener_reconnected
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:115:2026-09-25 19:34:09.238  3998-4046  InternalLogger          arenliel.musicwidget                 D
[DIAG_V5] [INTAKE] Recibido: Estado=PLAYING, Track=Rosa Pastel, Album=null, Duración=185941ms, Reason=media_notification
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:343:2026-09-25 19:34:42.355  3998-4038  InternalLogger          arenliel.musicwidget                 D
[DIAG_V5] [INTAKE] Recibido: Estado=OTHER(6), Track=Turning off the Rain, Album=mini mix vol. 1, Duración=-1ms, Reason=media_notification
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:459:2026-09-25 19:34:43.401  3998-4047  InternalLogger          arenliel.musicwidget                 D
[DIAG_V5] [INTAKE] Recibido: Estado=OTHER(6), Track=Turning off the Rain, Album=mini mix vol. 1, Duración=96761ms, Reason=media_notification
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:462:2026-09-25 19:34:43.770  3998-4047  InternalLogger          arenliel.musicwidget                 D
[DIAG_V5] [INTAKE] Recibido: Estado=PLAYING, Track=Turning off the Rain, Album=mini mix vol. 1, Duración=96761ms, Reason=media_notification
```

### 6. `IDENTITY_TRACE`
```text
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:13:2026-09-25 19:33:56.077  3998-4049  IDENTITY_TRACE          arenliel.musicwidget                 D
Paso4_reconcileNewSession: viejo=true, nuevo=true, coincide=true
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:21:2026-09-25 19:33:56.220  3998-4046  InternalLogger          arenliel.musicwidget                 D
[IDENTITY_TRACE] Paso1_trackChangedUI: viejo=false, nuevo=false, coincide=true, track=Rosa Pastel
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:99:2026-09-25 19:33:57.264  3998-4041  IDENTITY_TRACE          arenliel.musicwidget                 D
Paso4_reconcileNewSession: viejo=true, nuevo=false, coincide=false
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:118:2026-09-25 19:34:09.267  3998-4045  InternalLogger          arenliel.musicwidget                 D
[IDENTITY_TRACE] Paso1_trackChangedUI: viejo=false, nuevo=false, coincide=true, track=Rosa Pastel
C:\Users\arenliel\Desktop\logs-fdd6e89.txt:133:2026-09-25 19:34:09.286  3998-4038  InternalLogger          arenliel.musicwidget                 D
[IDENTITY_TRACE] Paso3_zombieDetector: viejo=true, nuevo=false, coincide=false
```

---
El reporte completo ha sido guardado exitosamente como un archivo markdown en el proyecto en `docs/reportes-ia/auditoria-log-diagnostico-showcase-ronda1.md`.
