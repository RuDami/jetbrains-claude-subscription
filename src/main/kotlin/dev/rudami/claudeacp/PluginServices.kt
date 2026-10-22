package dev.rudami.claudeacp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException

/**
 * Looks up one of this plugin's own application services, tolerating a plugin reload.
 *
 * Installing or updating the plugin without restarting unloads it and loads it again under a
 * new class loader, but does not stop work already running out of the old one — a background
 * provision, an npm install, an open settings dialog. When that work next asks for a service
 * it gets the *new* plugin's instance, and the cast to the old plugin's copy of the same
 * class fails:
 *
 *     class ClaudeAcpSettings cannot be cast to class ClaudeAcpSettings
 *     (... loader PluginClassLoader @57b24a00; ... loader PluginClassLoader @427e9a30)
 *
 * There is nothing to repair at that point: the caller belongs to a plugin the IDE has
 * already unloaded, and the copy that replaced it provisions on its own. So the leftover work
 * is cancelled instead, which a background task ends quietly, rather than reported as a
 * fatal error against a plugin version no longer installed.
 */
internal inline fun <reified T : Any> pluginService(): T = pluginService(T::class.java)

/**
 * The cast has to happen in here, not at the call site.
 *
 * `service<T>()` is inline and `getService` is erased to `Object`, so wrapping the call in a
 * `try` guards nothing: the compiler emits the `checkcast` on the value the inlined block
 * produced, past the end of the exception table. The catch was unreachable and the reload
 * still crashed. [Class.cast] throws inside the guarded frame, where it can be caught.
 */
internal fun <T : Any> pluginService(serviceClass: Class<T>): T {
    val instance = ApplicationManager.getApplication().getService(serviceClass)
        ?: throw IllegalStateException("Cannot find service ${serviceClass.name}")
    return ownInstance(serviceClass, instance)
}

/** [instance] as [serviceClass], or a cancellation when it belongs to a newer class loader. */
internal fun <T : Any> ownInstance(serviceClass: Class<T>, instance: Any): T =
    try {
        serviceClass.cast(instance)
    } catch (mismatch: ClassCastException) {
        LOG.info(
            "Abandoning work started before the plugin was reloaded: ${serviceClass.name} now " +
                "belongs to a newer class loader (${mismatch.message})",
        )
        throw ProcessCanceledException()
    }

private val LOG = logger<ClaudeAcpManager>()
