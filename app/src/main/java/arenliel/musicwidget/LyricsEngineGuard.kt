package arenliel.musicwidget

/**
 * Conjunto Letras-Relevo-Perdido-1: pure rule that answers one question — "does the lyrics engine
 * still correspond to the song that is playing?".
 *
 * Why it exists: the order "restart the lyrics engine because the song changed" used to travel on a
 * one-shot signal that lives in a single, cancellable processing pass. If a second notification
 * cancelled that pass (the music app publishes two notifications a few hundred milliseconds apart
 * when the song changes), the signal died with it and nobody restarted the engine for the new song.
 * This rule is evaluated from STATE (which song the engine was last told about vs. which song is
 * committed now), so any later pass can notice the mismatch and repair it.
 *
 * [engineIdentity] is the business identity (sessionIdentity, never trackKey) the engine was last
 * launched or scheduled for, or null if it was never launched / was torn down with the session.
 */
object LyricsEngineGuard {
    fun needsRecovery(engineIdentity: String?, currentIdentity: String, isSessionActive: Boolean): Boolean =
        isSessionActive && currentIdentity.isNotEmpty() && engineIdentity != currentIdentity
}
