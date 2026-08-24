package dev.stan.duolock

import dev.stan.duolock.duolingo.DuolingoAuth
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DuolingoAuthTest {

    private fun jwt(payloadJson: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"HS256"}""".toByteArray())
        val payload = enc.encodeToString(payloadJson.toByteArray())
        return "$header.$payload.signature"
    }

    @Test
    fun `extracts the numeric sub claim`() {
        assertEquals(123456789L, DuolingoAuth.userIdFromJwt(jwt("""{"sub":"123456789"}""")))
    }

    @Test
    fun `tolerates surrounding whitespace`() {
        assertEquals(42L, DuolingoAuth.userIdFromJwt("  ${jwt("""{"sub":"42"}""")}  "))
    }

    @Test
    fun `rejects a non-numeric sub`() {
        assertNull(DuolingoAuth.userIdFromJwt(jwt("""{"sub":"alice"}""")))
    }

    @Test
    fun `rejects a payload without sub`() {
        assertNull(DuolingoAuth.userIdFromJwt(jwt("""{"aud":"duolingo"}""")))
    }

    @Test
    fun `rejects junk that is not a jwt at all`() {
        assertNull(DuolingoAuth.userIdFromJwt("not a token"))
        assertNull(DuolingoAuth.userIdFromJwt(""))
        assertNull(DuolingoAuth.userIdFromJwt("a.b.c"))
    }
}
