# Auditoría — Ronda 6 (Identidad): Uso Real de `identityChanged` en `saveMusicInfo`

**Confirmación de Git Log (HEAD):**
```
3e85a7a (HEAD -> master) Conjunto Artwork-Final: Reemplazar previousApplied por previousLogical para sanear la detección de cambios
dcb4f54 Conjunto Artwork-Trace: Instrumentación de sincronía de archivos de portada
699229c Conjunto Artwork-Confirm: Registro de confirmación de persistencia real
```

---

## AU1. Código completo de `saveMusicInfo`

Ubicado en `MusicDataStore.kt` (Líneas 692 - 837).

```kotlin
692:     suspend fun saveMusicInfo(
693:         info: MusicInfo,
694:         forceUpdate: Boolean = false
695:     ): Boolean {
696:         var changed = false
697:         context.dataStore.edit { prefs ->
698: 
699:             val currentTitle =
700:                 prefs[TITLE]
701:                     ?: DEFAULT_TITLE
702: 
703:             val currentArtist =
704:                 prefs[ARTIST]
705:                     ?: DEFAULT_ARTIST
706: 
707:             val currentPackageName =
708:                 prefs[PACKAGE_NAME]
709:                     .orEmpty()
710: 
711:             val currentTrackKey =
712:                 prefs[TRACK_KEY]
713:                     .orEmpty()
714: 
715:             val currentArtworkKey =
716:                 prefs[ARTWORK_KEY]
717:                     .orEmpty()
718: 
719:             val currentArtworkUri =
720:                 prefs[ARTWORK_URI]
721:                     .orEmpty()
722: 
723:             val currentAppIconKey =
724:                 prefs[APP_ICON_KEY]
725:                     .orEmpty()
726: 
727:             val currentIsPlaying =
728:                 prefs[IS_PLAYING]
729:                     ?: false
730: 
731:             val currentIsSessionActive =
732:                 prefs[IS_SESSION_ACTIVE]
733:                     ?: false
734: 
735:             val currentLyric =
736:                 prefs[CURRENT_LYRIC]
737:                     .orEmpty()
738: 
739:             val currentLyricsTrackKey =
740:                 prefs[LYRICS_TRACK_KEY]
741:                     .orEmpty()
742: 
743:             val currentShowLyrics =
744:                 prefs[SHOW_LYRICS]
745:                     ?: true
746: 
747:             val currentPlaybackDeviceName =
748:                 prefs[PLAYBACK_DEVICE_NAME]
749:                     .orEmpty()
750: 
751:             val currentPlaybackDeviceType =
752:                 prefs[PLAYBACK_DEVICE_TYPE]
753:                     ?: 0
754: 
755:             val currentDurationMs =
756:                 prefs[DURATION_MS]
757:                     ?: 0L
758: 
759:             // 1. CAMBIO DE IDENTIDAD (Requiere reset de reloj)
760:             val identityChanged = currentTitle != info.title ||
761:                     currentArtist != info.artist ||
762:                     currentPackageName != info.packageName ||
763:                     currentTrackKey != info.trackKey ||
764:                     currentArtworkKey != info.artworkKey ||
765:                     currentArtworkUri != info.artworkUri ||
766:                     currentDurationMs != info.durationMs
767: 
768:             // 2. CAMBIO DE ESTADO DE REPRODUCCIÓN (Requiere reset de reloj)
769:             // Solo reseteamos si:
770:             // - El estado de Play/Pause cambió realmente.
771:             // - La sesión se cerró (isSessionActive: true -> false).
772:             // - El dispositivo de salida cambió.
773:             // NOTA: Si la sesión se reabre (false -> true) pero sigue en PAUSA, no reseteamos el reloj
774:             // para mantener el "Hace X horas" verídico.
775:             val playbackStatusChanged = currentIsPlaying != info.isPlaying ||
776:                     (currentIsSessionActive && !info.isSessionActive) ||
777:                     currentPlaybackDeviceName != info.playbackDeviceName ||
778:                     currentPlaybackDeviceType != info.playbackDeviceType
779: 
780:             // 3. CAMBIO DE METADATOS SECUNDARIOS (NO requiere reset de reloj)
781:             val metadataOnlyChanged = currentAppIconKey != info.appIconKey ||
782:                     currentLyric != info.currentLyric ||
783:                     currentLyricsTrackKey != info.lyricsTrackKey ||
784:                     currentShowLyrics != info.showLyrics
785: 
786:             val hasAnyChange = identityChanged || playbackStatusChanged || metadataOnlyChanged
787: 
788:             /*
789:              * Si nada ha cambiado y no se requiere actualización forzada,
790:              * salimos para evitar ruido en el Flow.
791:              */
792:             if (!hasAnyChange && !forceUpdate) {
793:                 return@edit
794:             }
795: 
796:             changed = true
797:             /*
798:              * Actualizamos todos los valores.
799:              */
800:             prefs[TITLE] = info.title
801:             prefs[ARTIST] = info.artist
802:             prefs[PACKAGE_NAME] = info.packageName
803:             prefs[TRACK_KEY] = info.trackKey
804:             prefs[SESSION_UUID] = info.sessionUUID
805:             prefs[ARTWORK_KEY] = info.artworkKey
806:             prefs[ARTWORK_URI] = info.artworkUri
807:             prefs[APP_ICON_KEY] = info.appIconKey
808:             prefs[IS_PLAYING] = info.isPlaying
809:             prefs[IS_SESSION_ACTIVE] = info.isSessionActive
810:             prefs[IS_PENDING_COMMIT] = info.isPendingCommit
811:             prefs[LAST_MAX_POSITION_MS] = info.lastMaxPositionMs
812:             prefs[IDENTITY_SCHEMA_VERSION] = CURRENT_IDENTITY_VERSION
813:             prefs[ALBUM] = info.album
814:             prefs[CURRENT_LYRIC] = info.currentLyric
815:             prefs[LYRICS_TRACK_KEY] = info.lyricsTrackKey
816:             prefs[SHOW_LYRICS] = info.showLyrics
817:             prefs[PLAYBACK_DEVICE_NAME] = info.playbackDeviceName
818:             prefs[PLAYBACK_DEVICE_TYPE] = info.playbackDeviceType
819:             prefs[DURATION_MS] = info.durationMs
820: 
821:             /*
822:              * lastUpdateEpoch representa una actualización real de la sesión (Epoch).
823:              * observedAtRealtime representa el anclaje monotónico.
824:              *
825:              * REGLA v6.6: Reseteamos el reloj ante cambios de identidad O cambios de estado 
826:              * de reproducción (incluyendo el paso a PAUSA) para que el umbral de 15 min 
827:              * cuente desde el momento exacto de la inactividad.
828:              */
829:             val shouldResetClock = identityChanged || playbackStatusChanged
830: 
831:             if (shouldResetClock) {
832:                 prefs[LAST_UPDATE_EPOCH] = System.currentTimeMillis()
833:                 prefs[OBSERVED_AT_REALTIME] = android.os.SystemClock.elapsedRealtime()
834:             }
835:         }
836:         return changed
837:     }
```

---

## Análisis de Uso de `identityChanged`

Inmediatamente después de calcularse en la línea 760, `identityChanged` se utiliza en:

1.  **Evaluación de cambio total (Línea 786):** Participa en `hasAnyChange` para decidir si se aborta el guardado por redundancia.
2.  **Reset de Reloj (Línea 829):** Es el disparador principal para resetear `LAST_UPDATE_EPOCH` y `OBSERVED_AT_REALTIME`.

**Confirmación:** Si `identityChanged` es verdadero (por ejemplo, porque la duración cambió de 0ms a su valor real), el sistema **resetea incondicionalmente el reloj de tiempo relativo**, provocando que el widget diga "Reciente, hace poco" en lugar de mantener el tiempo acumulado de la canción.
