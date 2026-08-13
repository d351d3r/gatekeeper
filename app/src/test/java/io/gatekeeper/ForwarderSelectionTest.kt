package io.gatekeeper

import io.gatekeeper.util.ProfileForwarder
import io.gatekeeper.util.ProfileForwarder.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Выбор кросс-профильного реле (D4a). Резолв по строке действия неявный, и стороннее
 * приложение, объявившее `net.typeblog.shelter.action.*`, попадает в кандидаты наравне с
 * системным форвардером. До правки брался первый кандидат, чей пакет не наш.
 */
class ForwarderSelectionTest {
    private val systemForwarder =
        Candidate("android", "com.android.internal.app.ForwardIntentToManagedProfile")

    @Test
    fun picksSystemForwarder() {
        assertEquals(systemForwarder, ProfileForwarder.pickForwarder(listOf(systemForwarder)))
    }

    /** Порядок кандидатов задает система, и чужое приложение может стоять первым. */
    @Test
    fun picksSystemForwarderAheadOfAnImpostor() {
        val impostor = Candidate("com.example.impostor", "com.example.impostor.RelayActivity")
        assertEquals(
            systemForwarder,
            ProfileForwarder.pickForwarder(listOf(impostor, systemForwarder))
        )
    }

    @Test
    fun rejectsThirdPartyOnly() {
        val impostors = listOf(
            Candidate("com.example.impostor", "com.example.impostor.RelayActivity"),
            Candidate("net.typeblog.shelter.dev2", "net.typeblog.shelter.ui.DummyActivity"),
        )
        assertNull(ProfileForwarder.pickForwarder(impostors))
    }

    /** Своя копия в этом же профиле реле не является: интент должен уйти в другой профиль. */
    @Test
    fun ignoresOwnPackage() {
        assertNull(
            ProfileForwarder.pickForwarder(
                listOf(Candidate("net.typeblog.shelter", "net.typeblog.shelter.ui.DummyActivity"))
            )
        )
    }

    @Test
    fun rejectsEmptyCandidates() {
        assertNull(ProfileForwarder.pickForwarder(emptyList()))
    }
}
