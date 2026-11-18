package com.lagradost.cloudstream3.plugins

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class PluginArtifactTest {
    @Test fun `identical plugin bytes are never downloaded again`() {
        val file = File.createTempFile("missa-plugin-", ".cs3")
        try {
            file.writeText("release-one")
            fun hash(text: String) = "sha256-" + MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
            assertTrue(PluginArtifact.isCurrent(file, hash("release-one")))
            assertTrue(PluginArtifact.isCurrent(file, hash("release-one").uppercase()))
            assertFalse(PluginArtifact.isCurrent(file, hash("release-two")))
            assertFalse(PluginArtifact.isCurrent(file, null))
            file.delete()
            assertFalse(PluginArtifact.isCurrent(file, hash("release-one")))
        } finally {
            file.delete()
        }
    }
}
