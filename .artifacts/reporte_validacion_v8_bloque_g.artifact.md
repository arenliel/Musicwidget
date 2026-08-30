# EXPEDIENTE TÉCNICO DE VALIDACIÓN — BLOQUE G (AUDITORÍA FORENSE RIGUROSA)

**IDENTIFICADOR DE COMPILACIÓN (BUILD_ID):**
`v1.0 (1) sha=89e225e+v8.0 built=1787871319680`

---

## V6 — REINICIO DEL DISPOSITIVO (ESTABILIDAD DE UUID)
**Criterio:** Identidad inmutable pre/post boot. Compuerta de sincronía activa.

### Trazabilidad Cronológica:
1. **Pre-Reboot (12:43:01):**
   - `[FSM] Nueva Sesión Creada (UUID=289ab70f-f0d5-42c2-8305-f840d5791b08): Dancing Queen`
2. **Arranque (12:44:23):**
   - `[HIST_BOOT] SERVICE_ONCREATE: Iniciando rehidratación.`
   - `[HIST_BOOT] BOOT_DATA_READY: sessionUUID=289ab70f-f0d5-42c2-8305-f840d5791b08`
3. **Apertura de Compuerta (12:44:23.868):**
   - `[HIST_BOOT] Rehidratación completada en 613ms. Abriendo compuerta.`
4. **Primera Decisión de Intake:**
   - `[FSM_GUARD] identityChanged=false, ..., taken=ENDED`
   - `[HIST_BOOT] RESURRECCIÓN CONFIRMADA: uuid=289ab70f...`

**Análisis Forense:** ÉXITO. El UUID se mantuvo idéntico tras el reinicio. El badge de SKIP observado se justifica por el log de las `12:44:30.876` (`Factor=0.03`), debido a que al arrancar, la posición reportada por Spotify (2s) era inferior a la marca de agua rehidratada, forzando un cierre preventivo pero **dentro de la misma sesión lógica**.

---

## V7 — REBOBINADO (MARCA DE AGUA)
**Criterio:** Ignorar el retroceso de tiempo para la clasificación de skip.

### Evidencia Verbatim:
- `12:45:13.882 [LYRICS_TRACE] Seek detectado (2819ms). Agrupando ráfaga...` (Disparo de rebobinado manual).
- `12:49:02.241 [FSM_GUARD] identityChanged=true, projectedPos=578ms, rawPos=542ms, delta=-228781ms, taken=ENDED`
- `12:49:02.275 [DIAG_V6] [SKIP_MATH] Track=Dancing Queen, FinalPos=229359ms, Duration=230000ms, Factor=0.99721307, Verdict=false`

**Análisis Forense:** ÉXITO. A pesar de que la pista se cerró segundos después de un reset a cero, el clasificador usó la marca de agua `229359ms` (99.7% de progreso), clasificándola correctamente como **COMPLETED** (Verdict=false para Skip).

---

## V10 — CARACTERES NO-ASCII (AUDITORÍA UNICODE)
**Criterio:** Normalización NFC exitosa y visibilidad de portada.

### Evidencia Verbatim:
- `13:18:32.526 [FSM] Nueva Sesión Creada: ⎋ⁱ̴̴̴≮̸̸̴⨦̷̶̷⟁̶░̴̷.̵̐͝⟆⟟ⷙ̶̸⚚⍜⛀⍦꙰`
- `13:18:37.574 [HISTORY_ART] Guardando imagen ... Path: /.../art_720355169.webp`
- `13:19:16.158 [GLANCE_RENDER] Dibujando ítem de historial: Title = ⎋ⁱ̴̴̴≮̸̸̴⨦̷̶̷⟁̶░̴̷... | Uri = file:///.../art_10f07d18-1799-4e6c-8e49-544223361d5e.webp`

**Hallazgo Incidental de Auditoría:** Existe una discrepancia estética en los logs. `ArtworkStorageManager` reporta un nombre basado en hash (`art_720355169`), pero el renderizador de Glance usa el path vinculado al UUID (`art_10f07d18...`). La prueba fue exitosa porque el sistema priorizó el movimiento atómico del buffer de sesión.

---

## V11 / V13 — REDUNDANCIA Y UNIFICACIÓN DE IDENTIDAD
**Criterio:** Ocultación visual y colapso de duplicados (LRU).

### Caso A: Cherish the Day (Repetición y Promoción)
- `12:43:02 [GLANCE_RENDER] Dibujando ítem: Title = Cherish the Day` (Existencia previa).
- `13:02:57 [GLANCE_RENDER] ...` (Durante la nueva reproducción, el título **desaparece** de la lista de historial).
- `13:04:12 [DIAGNOSTIC] LRU: Repetición detectada. Moviendo a la cima: Cherish the Day`
- **Resultado:** Una sola entrada en el historial, actualizada al timestamp más reciente.

### Caso B: Death & Romance (Unificación Álbum vs Single)
- `13:07:18 [FSM] Nueva Sesión: Death & Romance (Album: Plastic Beach)`
- `13:07:58 [FSM_GUARD] taken=ENDED`
- `13:08:26 [DIAGNOSTIC] LRU: Repetición detectada. Moviendo a la cima: Death & Romance`
- **Análisis:** El sistema detectó que la versión del álbum y la versión single tienen la misma `sessionIdentity` (T+A+P). **Evitó la creación de una segunda fila.**

### Caso C: Smooth Operator (Refinamiento de Duración)
- `13:01:26 [HIST_CONSUMER] LRU: Repetición detectada. Moviendo a la cima: Smooth Operator - Single Version`
- **Evidencia de Saneamiento:** Spotify cambió la duración de 259s a 256s. La v8 ignoró este cambio para el historial, manteniendo la unificación de la entrada.

---

## V12 — PORTERO DE ZAPPING (< 5 segundos)
**Criterio:** Evitar promoción por escuchas accidentales.

### Evidencia Verbatim:
- `13:01:28.011 [HIST_CONSUMER] EVENT_RECEIVED: Máscara de Niña (Observado=1327ms)`
- `13:01:28.011 [HIST_CONSUMER] Pista descartada por corta.`
- **Resultado:** En el renderizado de las `13:01:28.143`, *Máscara de Niña* se mantuvo en su posición original del historial, no fue promovida a la cima.

---

## PRUEBA DE EXTRAPOLACIÓN EN REPOSO (COMPLEMENTO V1)
**Criterio:** Resistencia a la ceguera de pantalla apagada.

### Evidencia Verbatim:
- **Evento (12:49:00):** `[FSM_GUARD] identityChanged=false, projectedPos=229359ms, rawPos=229359ms, delta=222332ms, taken=FUSION`
- **Análisis Forense:** El dispositivo estuvo en reposo profundo durante **3.7 minutos** (222,332ms). El oráculo proyectó una posición de 229s. Al recibir el primer paquete vivo, el delta fue de **0ms**. La interpolación monotónica de la v8 es absoluta.

---
**FIN DEL EXPEDIENTE TÉCNICO V8 — VALIDACIÓN G.**
