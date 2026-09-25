# Análisis de Logs — Estabilidad de Portadas (Fase 4: PlaybackContext)

Este reporte analiza el comportamiento del sistema tras migrar la sincronía visual al `PlaybackContext` de la sesión (commit `8c48dd7`).

## Punto 1 — Confirmación de versión
El log contiene la ejecución de la versión correcta:
- **Línea:** `2026-09-21 00:28:55.762  4842-4842  InternalLogger arenliel.musicwidget D [BUILD_ID] v1.0 (1) sha=8c48dd7 built=1789965014000`

## Punto 2 — Tiempo hasta sincronía exitosa
Todas las canciones alcanzaron sincronía visual (`resultado=true`) en menos de 1 segundo desde su detección.

| Canción | UUID | Tiempo (segundos) |
| :--- | :--- | :--- |
| This Song May Or May Not...* | `3161bbdd...` | 0.774s |
| 黄昏のBAY CITY | `15675882...` | 0.155s |
| Shiny kindness | `ba9f323c...` | 0.062s |
| 私達を信じていて... | `b891f15a...` | 0.652s |
| F・L・Y | `f04c2d44...` | 0.710s |
| Midnight Pretenders | `d51f90e8...` | 0.693s |
| 二人だけの海 | `1a7e269b...` | 0.620s |
| スカイレストラン... | `a7be8c87...` | 0.031s |
| 悲しみがとまらない... | `644fabd4...` | 0.076s |
| I Wanna Be With You | `29d94ec3...` | 0.023s |
| 01. Love Space... | `967998ef...` | 0.018s |
| 私小説 | `8c5038ab...` | 0.029s |
| Yours | `6abe64c5...` | 0.536s |
| まわれ　まわれ... | `d26c0915...` | 0.022s |
| Fantasy | `d0dabab1...` | 0.039s |
| 薄ら氷心中... | `8312780f...` | 0.027s |
| サンセット・ロード... | `8474ebad...` | 0.911s |
| トーキョーレギー... | `aa9723c3...` | 0.032s |
| Mystical Composer | `dd62aa5f...` | 0.583s |
| Darling... | `e8ddf913...` | 0.012s |
| Dream In The Street | `07ccc26b...` | 0.016s |
| ラム de ラブソング | `544de369...` | 0.017s |
| 街のドルフィン... | `58c3f09d...` | 0.471s |
| Tokai | `eb78e9aa...` | 0.845s |
| Love Was Really Gone | `13662e44...` | 0.026s |
| Purple Dream | `10b05b69...` | 0.399s |
| 真夜中のジョーク... | `5c50cc54...` | 0.450s |
| Street Dancer | `d6f59a4e...` | 0.011s |
| リフレイン - Refrain | `7871c6f9...` | 0.016s |

*\*Calculado desde rehidratación.*

## Punto 3 — Escrituras redundantes tras corrección de duración
Se confirma la **ausencia de escrituras redundantes** para una misma carátula tras la corrección de duración:
- Las canciones que llegaron inicialmente con duración `-1ms` (ej. `Tokai`, `リフレイン`, `Street Dancer`) solo registraron **una única escritura** de archivo sincronizado, a pesar de recibir múltiples actualizaciones de metadatos posteriores con la duración ya corregida. 
- Esto confirma que `confirmedArtworkKey` en `PlaybackContext` está filtrando correctamente las ráfagas.

## Punto 4 — Candados de duplicados
Se detectaron **33 bloqueos** por `contentKey` duplicado (`[DIAG_V7_KEY]`). El sistema continúa deduplicando ráfagas de posición de forma efectiva.

---
**Archivo guardado como:** `docs/reportes-ia/analisis_logs_artworks_8c48dd7.md`
