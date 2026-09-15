# Auditoría — Ronda 12 (Portadas): Código Completo de `createSnapshot`, Enfocado en el Origen Real de `artworkUri`

**Confirmación de Git Log (HEAD):**
```
e41110d (HEAD -> master) Revert "Conjunto Artwork-2: Guardar imagen resuelta en sesión activa y establecer respaldo de URI"
079837e Conjunto Artwork-2: Guardar imagen resuelta en sesión activa y establecer respaldo de URI
e54d9d0 Conjunto Artwork-1: Conservar Portada y Evitar Resolución Innecesaria (artIncoherent based)
```

---

## AH1. Código completo de `createSnapshot`

Ubicado en `MusicNotificationListener.kt` (Líneas 1676 - 1814).

```kotlin
1676:     private fun createSnapshot(
1677:         controller: MediaController,
1678:         metadata: MediaMetadata
1679:     ): MediaSnapshot? {
1680: 
1681:         val title =
1682:             metadata
1683:                 .getString(
1684:                     MediaMetadata.METADATA_KEY_TITLE
1685:                 )
1686:                 ?.takeIf {
1687:                     it.isNotBlank()
1688:                 }
1689:                 ?: return null
1690: 
1691:         val artist =
1692:             metadata
1693:                 .getString(
1694:                     MediaMetadata.METADATA_KEY_ARTIST
1695:                 )
1696:                 ?.takeIf {
1697:                     it.isNotBlank()
1698:                 }
1699:                 ?: "Unknown Artist"
1700: 
1701:         val album =
1702:             metadata.getString(
1703:                 MediaMetadata.METADATA_KEY_ALBUM
1704:             )
1705: 
1706:         val mediaId =
1707:             metadata.getString(
1708:                 MediaMetadata.METADATA_KEY_MEDIA_ID
1709:             )
1710: 
1711:         val artworkUri =
1712:             metadata
1713:                 .getString(
1714:                     MediaMetadata.METADATA_KEY_ART_URI
1715:                 )
1716:                 ?.takeIf {
1717:                     it.isNotBlank()
1718:                 }
1719:                 ?: metadata
1720:                     .getString(
1721:                         MediaMetadata
1722:                             .METADATA_KEY_ALBUM_ART_URI
1723:                     )
1724:                     ?.takeIf {
1725:                         it.isNotBlank()
1726:                     }
1727: 
1728:         val playbackState =
1729:             controller
1730:                 .playbackState
1731:                 ?.state
1732:                 ?: PlaybackState.STATE_NONE
1733: 
1734:         val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
1735:         val position = controller.playbackState?.position ?: 0L
1736:         
1737:         // REGLA: Usamos el caché para evitar sondeos en cada track change
1738:         val deviceName = cachedAudioDeviceName
1739:         val deviceType = cachedAudioDeviceType
1740: 
1741:         val trackKeyStr = "$title|$artist|$duration"
1742:         val myCoreKey = "$title|$artist".trim().lowercase()
1743: 
1744:         // FASE A: Captura Inmediata (Segundo 0)
1745:         // Intentamos extraer y clonar el bitmap del sistema mientras está fresco.
1746:         // v5.2.3: Se usa CoreKey como índice. UI-Only (Disk Shield). No toca el historial.
1747:         metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let { original ->
1748:             if (!memoryArtworkCache.containsKey(myCoreKey)) {
1749:                 runCatching {
1750:                     val clone = original.copy(original.config ?: Bitmap.Config.ARGB_8888, false)
1751:                     memoryArtworkCache[myCoreKey] = clone
1752:                     
1753:                     // DISK SHIELD (v5.1): Persistencia inmediata para Glance
1754:                     serviceScope.launch(Dispatchers.IO) {
1755:                         saveBitmapToDiskShield(clone)
1756:                     }
1757: 
1758:                     // RETOQUE ATÓMICO (v9.0): Escritura directa a ruta definitiva (Bloque B.2)
1759:                     currentLogicalSession?.let { session ->
1760:                         if (session.identity.title == sanitize(title) && session.identity.artist == sanitize(artist)) {
1761:                             serviceScope.launch(Dispatchers.IO) {
1762:                                 val historyDir = File(filesDir, "history")
1763:                                 if (!historyDir.exists()) historyDir.mkdirs()
1764:                                 val artworkFile = File(historyDir, "art_${session.sessionUUID}.webp")
1765:                                 val tempFile = File(historyDir, "art_${session.sessionUUID}.tmp")
1766:                                 
1767:                                 try {
1768:                                     val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
1769:                                         Bitmap.CompressFormat.WEBP_LOSSY
1770:                                     } else {
1771:                                         @Suppress("DEPRECATION")
1772:                                         Bitmap.CompressFormat.WEBP
1773:                                     }
1774:                                     FileOutputStream(tempFile).use { out ->
1775:                                         if (clone.compress(format, 80, out)) {
1776:                                             out.flush()
1777:                                             Files.move(tempFile.toPath(), artworkFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
1778:                                             InternalLogger.d(applicationContext, "[ART_LIFECYCLE] Retoque Atómico: Portada persistida directamente (UUID=${session.sessionUUID})")
1779:                                         }
1780:                                     }
1781:                                 } catch (e: Exception) {
1782:                                     Log.e(TAG, "Error en retoque atómico directo", e)
1783:                                 } finally {
1784:                                     if (tempFile.exists()) tempFile.delete()
1785:                                 }
1786:                             }
1787:                         }
1788:                     }
1789:                     
1790:                     InternalLogger.d(applicationContext, "[ART_LIFECYCLE] Fase A: Bitmap clonado en RAM y Disco para $title")
1791:                 }
1792:             }
1793:         }
1794: 
1795:         return MediaSnapshot(
1796:             packageName = controller.packageName,
1797:             title = title,
1798:             artist = artist,
1799:             album = album,
1800:             mediaId = mediaId,
1801:             artworkUri = artworkUri,
1802:             playbackState = playbackState,
1803:             isSessionActive = true,
1804:             playbackDeviceName = deviceName,
1805:             playbackDeviceType = deviceType,
1806:             durationMs = duration,
1807:             positionMs = position,
1808:             recordedAt = System.currentTimeMillis(),
1809:             artworkSource = ArtworkSource.Placeholder,
1810:             observedAtRealtime = SystemClock.elapsedRealtime(),
1811:             positionUpdatedAtRealtime = controller.playbackState?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
1812:             playbackSpeed = controller.playbackState?.playbackSpeed ?: 1.0f
1813:         )
1814:     }
```

**Origen real de `artworkUri`:**
Se copia directamente de los metadatos de la sesión multimedia (Líneas 1711 - 1726). Intenta primero `METADATA_KEY_ART_URI` y, si falla, cae a `METADATA_KEY_ALBUM_ART_URI`. Si ambas están vacías, el campo queda en `null`.

**Conversión de Bitmap:**
La función **NO** convierte el bitmap en archivo para el campo `artworkUri`. El bitmap extraído en la "Fase A" se guarda en archivos físicos con nombres fijos (`ALBUM_ART_FILE`, etc.) o en el historial (`art_${uuid}.webp`), pero **estos archivos no actualizan el campo `artworkUri` del snapshot** — este campo solo lleva la URI original de la app de música.

---

## AH2. ¿Esta función depende de algo que los Conjuntos Cierres pudieron haber alterado?

**SÍ.**

1.  **`currentLogicalSession` (Línea 1759):** Esta función lee la sesión global para el "Retoque Atómico". Si la serie Cierres alteró el momento en que se limpia o crea la sesión, esto afecta si el retoque atómico encuentra o no una sesión válida a la que asociar la imagen.
2.  **`memoryArtworkCache` (Línea 1748):** Leída y escrita para deduplicación RAM.
3.  **`cachedAudioDeviceName/Type` (Líneas 1738-1739):** Leídos para el contexto de audio.

**Confirmación de independencia:** NO lee `lastAppliedSnapshot` ni `lastLogicalSnapshot`. Su construcción es puramente a partir de los metadatos vivos del `MediaController`.
