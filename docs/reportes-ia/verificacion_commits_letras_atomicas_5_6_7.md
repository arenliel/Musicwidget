# VERIFICACIÓN — LOS 3 COMMITS DE LETRAS-ATOMICAS 5, 6 Y 7

## 1. `git log --oneline -5`
```text
b41be3e (HEAD -> master) Conjunto Letras-Atomicas-7: validar que la respuesta de la API de LRCLIB corresponda al artista y título solicitados
de524b9 Conjunto Letras-Atomicas-6: serializar el reinicio del ticker de letras con synchronized(lyricsLock)
2239252 Conjunto Letras-Atomicas-5: releer MusicStateProvider.current() justo antes de getLyrics tras el debounce
988bdc4 Guardar reporte de verificación de estado real del código actual
4612cdd Actualizar reporte de auditoría runLyricsShowcase previo al parche final
```

---

## 2. `git show <SHA> --stat` para los últimos 3 commits

### A. Letras-Atómicas-5 (`22392520831d53cedfe2124b5b7f224cba49e357`)
```text
commit 22392520831d53cedfe2124b5b7f224cba49e357
Author: arenliel <alvz.angel12@gmail.com>
Date:   Fri Sep 25 15:01:15 2026 -0400

    Conjunto Letras-Atomicas-5: releer MusicStateProvider.current() justo antes de getLyrics tras el debounce

 .../java/com/example/musicwidget/MusicNotificationListener.kt  | 10 +++++++---\
 1 file changed, 7 insertions(+), 3 deletions(-)
```

### B. Letras-Atómicas-6 (`de524b93762d7a351d4c06d6e100f6ae850b95fc`)
```text
commit de524b93762d7a351d4c06d6e100f6ae850b95fc
Author: arenliel <alvz.angel12@gmail.com>
Date:   Fri Sep 25 15:03:37 2026 -0400

    Conjunto Letras-Atomicas-6: serializar el reinicio del ticker de letras con synchronized(lyricsLock)

 .../musicwidget/MusicNotificationListener.kt       | 53 ++++++++++++----------\
 1 file changed, 30 insertions(+), 23 deletions(-)
```

### C. Letras-Atómicas-7 (`b41be3e459ae71f4d35e2d8025deaa2adf4d7688`)
```text
commit b41be3e459ae71f4d35e2d8025deaa2adf4d7688 (HEAD -> master)
Author: arenliel <alvz.angel12@gmail.com>
Date:   Fri Sep 25 15:05:40 2026 -0400

    Conjunto Letras-Atomicas-7: validar que la respuesta de la API de LRCLIB corresponda al artista y título solicitados

 app/src/main/java/arenliel/musicwidget/LyricsRepository.kt | 12 ++++++++++++\
 1 file changed, 12 insertions(+)
```

---

## 3. `git show b41be3e459ae71f4d35e2d8025deaa2adf4d7688 -- LyricsRepository.kt` (Diff completo y literal)
```diff
commit b41be3e459ae71f4d35e2d8025deaa2adf4d7688
Author: arenliel <alvz.angel12@gmail.com>
Date:   Fri Sep 25 15:05:40 2026 -0400

    Conjunto Letras-Atomicas-7: validar que la respuesta de la API de LRCLIB corresponda al artista y título solicitados

diff --git a/app/src/main/java/arenliel/musicwidget/LyricsRepository.kt b/app/src/main/java/arenliel/musicwidget/LyricsRepository.kt
index e60ec33..1f0e470 100644
--- a/app/src/main/java/arenliel/musicwidget/LyricsRepository.kt
+++ b/app/src/main/java/arenliel/musicwidget/LyricsRepository.kt
@@ -75,6 +75,18 @@ class LyricsRepository(private val context: Context) {
             if (connection.responseCode == 200) {
                 val response = connection.inputStream.bufferedReader().use { it.readText() }
                 val json = JSONObject(response)
+                // Conjunto Letras-Atomicas-5: LRCLIB es una base colaborativa y puede
+                // tener entradas mal etiquetadas — verificamos que lo que devuelve
+                // realmente corresponda al artista/título que pedimos antes de confiar
+                // en su contenido.
+                val returnedTrack = json.optString("trackName")
+                val returnedArtist = json.optString("artistName")
+                val matchesRequest = normalizeForSearch(returnedTrack).equals(normalizeForSearch(title), ignoreCase = true) &&
+                    normalizeForSearch(returnedArtist).equals(normalizeForSearch(artist), ignoreCase = true)
+                if (!matchesRequest) {
+                    Log.e("LyricsRepo", "Respuesta de LRCLIB no coincide con lo solicitado: pedido=$artist|$title, recibido=$returnedArtist|$returnedTrack")
+                    return@withContext null
+                }
                 return@withContext json.optString("syncedLyrics").takeIf { it.isNotBlank() }
             }
         } catch (e: Exception) {
```
