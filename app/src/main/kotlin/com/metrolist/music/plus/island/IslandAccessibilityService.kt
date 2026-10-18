/**
 * Metrolist Plus — servicio de accesibilidad de la Dynamic Island.
 * Solo existe para obtener una ventana TYPE_ACCESSIBILITY_OVERLAY, la única que Android dibuja por encima de la
 * barra de estado y que recibe toques ahí (una TYPE_APPLICATION_OVERLAY queda debajo de los íconos del sistema).
 * No lee la pantalla ni recibe eventos útiles: canRetrieveWindowContent = false.
 */

package com.metrolist.music.plus.island

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PlusIslandAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        connectedService.value = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    private fun disconnect() {
        if (connectedService.value === this) connectedService.value = null
    }

    companion object {
        private val connectedService = MutableStateFlow<PlusIslandAccessibilityService?>(null)

        /** The running service while the user has it enabled in the system accessibility settings. */
        val connected: StateFlow<PlusIslandAccessibilityService?> = connectedService.asStateFlow()
    }
}

fun Context.openIslandAccessibilitySettings() {
    // The fragment args highlight our service on the settings screens that support it.
    val component = ComponentName(this, PlusIslandAccessibilityService::class.java).flattenToString()
    runCatching {
        startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .putExtra(":settings:fragment_args_key", component)
                .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", component) })
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
