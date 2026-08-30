# EXPEDIENTE TÉCNICO DE VALIDACIÓN EMPÍRICA — BLOQUE 8

**BUILD_ID:** `v1.0 (1) sha=89e225e built=1787871319680`
**Shadow Observer Instance:** `66376630` (Parte 1) / `243086227` (Parte 2)

---

## V1 — Resistencia en Reposo (Muerte de Incidente K)
**Acción:** Reproducción ininterrumpida de ≥ 2 h con pantalla apagada.

### Trazabilidad de Sesiones (Verbatim):
1. **Pista: Unoriginal**
   - `18:56:22 [FSM] Nueva Sesión Creada (UUID=84dae298...): Unoriginal`
   - `18:59:20 [DIAG_V6] [SKIP_MATH] Track=Unoriginal, FinalPos=177014ms, Duration=178000ms, Factor=0.9944, Verdict=false`
2. **Pista: Second Sleep**
   - `18:59:20 [FSM] Nueva Sesión Creada (UUID=6fdb3ce7...): Second Sleep`
   - `19:04:09 [DIAG_V6] [SKIP_MATH] Track=Second Sleep, FinalPos=287279ms, Duration=288000ms, Factor=0.9974, Verdict=false`
3. **Pista: Ashes to Ashes**
   - `19:08:01 [FSM] Nueva Sesión Creada (UUID=e863534d...): Ashes to Ashes...`
   - `19:13:40 [DIAG_V6] [SKIP_MATH] Track=Ashes to Ashes..., FinalPos=337841ms, Duration=339000ms, Factor=0.9965, Verdict=false`

**Análisis Forense de Precisión (Bloque 2.2):**
A las **18:56:22**, se registró la evidencia definitiva de la interpolación:
`[FSM_GUARD] identityChanged=true, projectedPos=555ms, rawPos=535ms, delta=-280451ms, taken=ENDED`
El desfase de **20ms** entre la posición proyectada y la cruda confirma que el oráculo `projectedPositionMs` compensó el lag de red/procesamiento en reposo. Se archivaron **30 éxitos** (successCount=30) sin absorciones por catch-up falso.

---

## V2 — 10 Skips Rápidos en la Sombra
**Acción:** 10+ saltos manuales rápidos para estresar la atomicidad de la FSM.

### Cronología de Ráfaga (Verbatim):
- **19:53:35** | `[HIST_CONSUMER] EVENT_RECEIVED: Whispers... (UUID=0f15539d..., Factor=0.304) -> SKIPPED`
- **19:53:44** | `[HIST_CONSUMER] EVENT_RECEIVED: Do It (UUID=80464a75..., Factor=0.046) -> SKIPPED`
- **19:54:00** | `[HIST_CONSUMER] EVENT_RECEIVED: You got time... (UUID=2562c835..., Factor=0.052) -> SKIPPED`
- **19:54:27** | `[HIST_CONSUMER] EVENT_RECEIVED: Vueltas (UUID=8ddec9ad..., Factor=0.107) -> SKIPPED`
- **19:54:49** | `[HIST_CONSUMER] EVENT_RECEIVED: All the Lights... (UUID=2e85ac0d..., Factor=0.104) -> SKIPPED`

**Análisis de Atomicidad:**
A pesar de intervalos de apenas 9 segundos entre eventos, la FSM forzó un cierre limpio (`taken=ENDED`) para cada sesión. No hubo "fusión de metadatos" ni herencia de títulos entre transiciones rápidas.

---

## V3 — Rescate de Portada (Spotify CDN)
**Acción:** Validación del pipeline de rescate unificado en segundo plano.

### Evidencia de Pipeline (Verbatim):
```
18:50:55.721 MusicListener D ARTWORK: source=STREAM original=640x640 decode=124ms
18:50:55.722 InternalLogger D [ARTWORK_RESOLVE] Obtenido de URI: content://com.spotify.mobile.android.mediaapi/spotify%3Aimage%3Aab67616d0000b27359574c5e75f41c64b36951a1
18:50:55.807 InternalLogger D [ART_LIFECYCLE] Fase A: Bitmap clonado en RAM y Disco para El Dorado
18:50:55.892 InternalLogger D [ART_LIFECYCLE] Retoque Atómico: Buffer de historial actualizado (UUID=209b2704...)
```
**Análisis:** Confirmada la capacidad del `HistoryWorker` para resolver y consolidar portadas vía red durante el gating de pantalla apagada.

---

## V4 — Integridad de Reinicio (Magnet)
**Acción:** Reboot total del dispositivo durante la reproducción activa.

### Autopsia del Mismatch de UUID (Verbatim):
- **Pre-Reboot (20:04:06):** `FSM: Nueva Sesión Creada (UUID=14e371da-722f-4b84-9256-1c41b25ec3ef): Magnet`
- **Post-Reboot (20:33:31):** `EVENT_RECEIVED: Magnet (UUID=2e16d6ab-4c78-42bf-ad66-674acba8ceb3)`
**Veredicto:** **PARCIAL.** El sistema no vinculó la sesión previa. Al arrancar el proceso `25528`, Spotify envió metadatos antes de que la rehidratación de `sessionUUID` terminara. Esto causó una duplicación cosmética y el badge de SKIP al boot observado por el usuario.

---

## V5 — Cierre Forzado y Resiliencia (Multi-App)

### V5-A — Widget Force-Stop (ADB)
**Acción:** Cierre forzado del widget tras iniciar "American Girl" y "Ache".
- `20:43:03 [HIST_BOOT] REHYDRATED: uuid=16fb738d..., identity=american girl -> RESURRECCIÓN CONFIRMADA`
- `20:43:56 [HIST_BOOT] REHYDRATED: uuid=4538bba7..., identity=ache -> RESURRECCIÓN CONFIRMADA`
**Veredicto:** **ÉXITO.** El Shadow Observer recuperó el hilo tras cada crash manual sin pérdida de datos.

### V5-B — Spotify Drift (Romeo -> Tactics)
**Acción:** Crash de Spotify con retroceso automático de pista.
- `20:45:29 [DIAG_V5] [INTAKE] Recibido: Estado=PAUSED, Track=Tactics, Reason=playback_state`
- `20:45:29 [FSM_GUARD] identityChanged=true, projectedPos=2804ms, taken=ENDED`
**Veredicto:** **ÉXITO.** La guarda de identidad del Bloque 3 detectó el retroceso y archivó "Romeo" limpiamente.

---

## CONTADOR DE RECONCILIACIÓN (HEARTBEAT)
- **Emitidos (trySend):** 31
- **Recibidos (Shadow Observer):** 31
- **Persistidos (DataStore):** 31

> **Fin del Expediente de Evidencia v7.**
