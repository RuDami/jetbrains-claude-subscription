package com.intellij.ml.llm.core.chat.ui

import javax.swing.Icon

/**
 * Compile-time stand-in for AI Assistant's own interface, which is internal API and lives in
 * a module jar inside an IDE installation rather than in any published artifact.
 *
 * Declaring it here is what lets this plugin build on a machine — CI included — that has no
 * JetBrains IDE and no AI Assistant installed. This source set is compileOnly: the class is
 * never packaged, and at runtime the real one comes from the AI Assistant plugin, which the
 * optional dependency in plugin.xml guarantees is present whenever our implementation loads.
 *
 * Verified against 262.9437.276, where the interface declares exactly one abstract method.
 * If AI Assistant ever adds another, our implementation stops satisfying it and the icon
 * extension fails to load — which is why it sits behind an optional descriptor.
 */
interface AgentIconService {
    fun loadIconForAgent(agentId: String): Icon
}
