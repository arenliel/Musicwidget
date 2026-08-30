# Walkthrough — Orden de Trabajo v9 — Custodia de Arte y Monotonía

La Orden v9 cierra las brechas de integridad detectadas tras el incidente "hotline." y estabiliza definitivamente la clasificación de skips.

## Mejoras de Arquitectura

### 1. Fin de la Cadena de Custodia (Artwork)
Se ha eliminado el "buffer intermedio" de portadas. Ahora, el sistema escribe el archivo directamente en su ubicación final (`art_{sessionUUID}.webp`) desde el primer segundo.
- **Beneficio**: Las portadas sobreviven a reinicios del servicio incluso si la canción está en pausa prolongada.
- **Seguridad**: Se eliminó la limpieza indiscriminada de archivos temporales al arranque.

### 2. Marca de Agua Inviolable
Se ha centralizado el seguimiento del progreso máximo (`maxPositionMs`) en la `LogicalSession`.
- **Monotonía**: El récord de reproducción nunca retrocede.
- **Boot Fix**: El sistema ahora ignora retrocesos de tiempo de Spotify durante el arranque, eliminando los badges de "Skip" falsos que aparecían tras reiniciar el móvil.

### 3. Sello de Build Real
Se corrigió la lógica de Gradle para que el `BUILD_ID` refleje la realidad:
- `sha`: Procede de git.
- `built`: Timestamp real del último commit de git.
Esto permite certificar qué código está corriendo realmente en el dispositivo.

## Resultados de Auditoría (Regla 11)
Se ha implementado el desacoplamiento estricto del UUID:
- Los Snapshots son anónimos (no portan el UUID).
- Solo la Sesión Lógica conoce su identidad física.
- Esto previene colisiones y divergencias de datos en el Shadow Observer.

## Instrucciones para el usuario
He implementado una opción de reseteo masivo de rachas (`skipStreak`) para limpiar los datos inflados por errores antiguos. Está **desactivada por defecto**. Si deseas activarla una sola vez para "limpiar la casa", házmelo saber.

---
**Build v9.0-Alpha lista para validación G.**
