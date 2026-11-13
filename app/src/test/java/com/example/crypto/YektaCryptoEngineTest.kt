package com.example.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class YektaCryptoEngineTest {

    @Test
    fun encryptAndDecryptRoundtrip_succeedsWithHybridKeyDerivation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = YektaCryptoEngine(context)
        val pubKey = engine.getPublicKeyBase64()
        val plaintext = "Yekta şifreli mesaj testi 123"

        val envelope = engine.encryptForPeer(plaintext, pubKey)
        assertNotEquals(plaintext, envelope.ciphertextB64)

        val decrypted = engine.decryptFromPeer(
            ciphertextB64 = envelope.ciphertextB64,
            nonceB64 = envelope.nonceB64,
            ephemeralPubKeyB64 = envelope.ephemeralPubKeyB64
        )
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun contentHasher_detectsExactAndNearDuplicates() {
        val h1 = ContentHasher.sha256Hex("  Merhaba   Yekta Dünyası ")
        val h2 = ContentHasher.sha256Hex("merhaba yekta dünyası")
        assertEquals(h1, h2)

        val sim1 = ContentHasher.computeTextSimHash("Gizlilik odaklı merkeziyetsiz sosyal ağ deneyimi")
        val sim2 = ContentHasher.computeTextSimHash("Gizlilik odaklı merkeziyetsiz sosyal ağ deneyimi!")
        assertTrue(ContentHasher.hammingDistanceHex(sim1, sim2) <= 4)
    }
}
