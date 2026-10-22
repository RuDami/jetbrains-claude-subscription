package dev.rudami.claudeacp

import com.intellij.openapi.progress.ProcessCanceledException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class PluginServicesTest {

    @Test
    fun `returns the service when it is our own copy of the class`() {
        val settings = ClaudeAcpSettings()
        assertSame(settings, ownInstance(ClaudeAcpSettings::class.java, settings))
    }

    /**
     * What a reload looks like from here: the service registry hands back an instance of a
     * class this caller cannot see. It must cancel, not throw ClassCastException — the first
     * attempt guarded the lookup rather than the cast, and the cast ran outside the `try`.
     */
    @Test
    fun `cancels when the service belongs to another class loader`() {
        assertFailsWith<ProcessCanceledException> {
            ownInstance(ClaudeAcpSettings::class.java, Any())
        }
    }
}
