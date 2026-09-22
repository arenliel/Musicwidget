# Análisis de Logs — Reinicio de Servicio (Validación Fase 5)

Este reporte analiza el comportamiento del sistema tras un reinicio del servicio, evaluando la persistencia de la sincronía visual de la portada (commit `ed26936`).

## Punto 1 — Confirmación de versión
El log confirma la ejecución de la versión solicitada:
- **Línea:** `2026-09-22 13:42:50.641  4054-4054  InternalLogger arenliel.musicwidget D [BUILD_ID] v1.0 (1) sha=ed26936 built=1790007416000`

## Punto 2 — Ventana de rehidratación y escritura

Se identifica el momento de la reconexión del servicio tras un reinicio:
- **Rehidratación completada:** `2026-09-22 13:46:15.800`

Dentro de los 5 segundos posteriores, se detecta la siguiente escritura de carátula:
- **Timestamp:** `2026-09-22 13:46:16.401`
- **Línea:** `2026-09-22 13:46:16.401 4054-4143 InternalLogger arenliel.musicwidget D [ART_TRACE] Escribiendo archivo sincronizado: key=https://yt3.googleusercontent.com/c4VEWZqZ8rlODHfat1tnaErQPN9LHGLmu47wDdErUw0PeV1OdITYWgmlG26tuVAKiH-QX0tGikHWGSk=w1080-h1080-p-l90-rj, UUID=0e691e0c-c0cf-42fd-9c0a-ac47071bd25c`

## Punto 3 — Comparación de claves (Deduplicación post-reinicio)

- **Valor antes del reinicio (13:43:38.950):** `key=https://yt3.googleusercontent.com/c4VEWZqZ8rlODHfat1tnaErQPN9LHGLmu47wDdErUw0PeV1OdITYWgmlG26tuVAKiH-QX0tGikHWGSk=w1080-h1080-p-l90-rj`
- **Valor tras el reinicio (13:46:16.401):** `key=https://yt3.googleusercontent.com/c4VEWZqZ8rlODHfat1tnaErQPN9LHGLmu47wDdErUw0PeV1OdITYWgmlG26tuVAKiH-QX0tGikHWGSk=w1080-h1080-p-l90-rj`

**Hecho:** La escritura realizada tras la rehidratación es **idéntica** a la que ya estaba persistida antes del reinicio.

**Observación adicional del log:**
Se detectó una escritura vacía previa que limpió el archivo de sincronía antes de la escritura final:
- `2026-09-22 13:46:16.092 ... [ART_SYNC_TRACE] Archivo escrito y confirmado: fileName=album_art.key, texto=, timestamp=1790099176092`

Esta limpieza ocurrió durante el procesamiento inicial por `Reason=listener_reconnected`, lo cual provocó que el chequeo posterior detectara una incoherencia visual (`ArtIncoherent=true`) y forzara la re-escritura de la clave idéntica.
