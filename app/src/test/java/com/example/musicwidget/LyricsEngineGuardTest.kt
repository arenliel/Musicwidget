package arenliel.musicwidget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Conjunto Letras-Relevo-Perdido-1: tests for LyricsEngineGuard (pure logic, no Android).
 * Identities are generic markers; what is tested is the decision, not any song.
 */
class LyricsEngineGuardTest {

    @Test
    fun elMotorSigueEnLaCancionAnterior_hayQueRecuperar() {
        assertTrue(LyricsEngineGuard.needsRecovery("pkg|cancion a|artista a", "pkg|cancion b|artista b", true))
    }

    @Test
    fun elMotorYaFueAvisadoDeLaCancionVigente_noHaceFalta() {
        assertFalse(LyricsEngineGuard.needsRecovery("pkg|cancion b|artista b", "pkg|cancion b|artista b", true))
    }

    @Test
    fun elMotorNuncaSeLanzo_hayQueRecuperar() {
        assertTrue(LyricsEngineGuard.needsRecovery(null, "pkg|cancion b|artista b", true))
    }

    @Test
    fun sinSesionActiva_nuncaSeRecupera() {
        assertFalse(LyricsEngineGuard.needsRecovery(null, "pkg|cancion b|artista b", false))
        assertFalse(LyricsEngineGuard.needsRecovery("pkg|cancion a|artista a", "pkg|cancion b|artista b", false))
    }

    @Test
    fun identidadVigenteVacia_nuncaSeRecupera() {
        assertFalse(LyricsEngineGuard.needsRecovery(null, "", true))
        assertFalse(LyricsEngineGuard.needsRecovery("pkg|cancion a|artista a", "", true))
    }

    @Test
    fun laIdentidadDeNegocioNoIncluyeLaDuracion_unaCorreccionDeDuracionNoDisparaRecuperacion() {
        // Both sides are sessionIdentity (package|title|artist). A late duration correction changes
        // trackKey but not this value, so the engine is not restarted because of it.
        val identity = "pkg|cancion b|artista b"
        assertFalse(LyricsEngineGuard.needsRecovery(identity, identity, true))
    }
}
