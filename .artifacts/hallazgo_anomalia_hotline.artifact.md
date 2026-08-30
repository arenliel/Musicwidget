# INFORME DE ANOMALÍA — CASO "HOTLINE." (AUDITORÍA RIGUROSA)

**SÍNTOMA:** La canción "hotline." de "proloxx" se guardó en el historial sin portada tras una sesión de latencia prolongada (~35 minutos).

---

## 1. TRAZABILIDAD DE CAPTURA (SEGUNDO 0)
El sistema capturó exitosamente el arte al inicio de la reproducción y lo vinculó al UUID de la sesión activa.

### Verbatim de Logs:
```
[13:54:35] DEBUG: [ART_LIFECYCLE] Fase A: Bitmap clonado en RAM y Disco para hotline.
[13:54:35] DEBUG: [ART_LIFECYCLE] Retoque Atómico: Buffer de historial actualizado (UUID=8ed211ff-e8b0-4b7c-bc95-6ee6119b7a14)
```
**Estatus:** Correcto. El archivo `buf_8ed211ff...webp` fue creado físicamente en el disco.

---

## 2. DETECCIÓN DE ENVENENAMIENTO (HIST_POISON)
Durante el cierre de la sesión (cuando entró la pista "bully"), el detector de la v8 capturó una discrepancia de integridad crítica.

### Verbatim de Logs:
```
[14:30:11] DEBUG: [HIST_CHANNEL] EVENT_SENT: Success=true, Failure=false, Closed=false, Track=hotline.
[14:30:11] WARN : [HIST_POISON] Discrepancia de UUID detectada: Expected=8ed211ff-e8b0-4b7c-bc95-6ee6119b7a14, Start=, End=
```
**Análisis Técnico:**
- El campo `Expected` tiene el UUID correcto de la sesión.
- Los campos `Start` (birthSnapshot) y `End` (liveSnapshot) están **VACÍOS**.
- **Causa Raíz:** Aunque la `LogicalSession` posee el UUID, los snapshots que esta contiene nacieron sin él y nunca fueron actualizados. Esto rompe el vínculo lógico entre la persistencia de datos y el archivo de imagen en el disco durante el commit tardío.

---

## 3. CONSOLIDACIÓN FALLIDA
El sistema intentó realizar el movimiento atómico, pero bajo una identidad "huérfana".

### Verbatim de Logs:
```
[14:30:11] DEBUG: [SHADOW_OBSERVER] Portada consolidada para: com.spotify.music|hotline.|proloxx|corrupted files|135000 (from UUID 8ed211ff-e8b0-4b7c-bc95-6ee6119b7a14)
[14:30:11] DEBUG: [DIAG_V6] [SKIP_MATH] Track=hotline., FinalPos=101163ms, Duration=135000ms, Factor=0.74935555, Verdict=false
```
**Análisis Técnico:**
A pesar del log de "consolidación", si los snapshots no portan el UUID (como demostró el Bloque 2), el Shadow Observer no tiene una referencia interna sólida para garantizar que el archivo `buf_...` siga allí, especialmente si hubo un ciclo de limpieza o reinicio del servicio durante la latencia.

---

## CONCLUSIÓN DE LA AUDITORÍA
El Incidente "hotline." revela una **vulnerabilidad de herencia**. Los snapshots multimedia son "ciegos" al UUID de la sesión que los contiene.

### Acción Requerida (Próxima Orden):
Modificar `createSnapshot` y la actualización de `liveSnapshot` en la FSM para inyectar obligatoriamente el `sessionUUID` de la `LogicalSession` activa. Esto asegurará que la cadena de custodia del artwork sea inquebrantable incluso en periodos de inactividad extremos.

> **Fin del Informe de Anomalía.**
