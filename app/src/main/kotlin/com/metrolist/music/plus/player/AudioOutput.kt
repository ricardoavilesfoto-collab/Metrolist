/**
 * Metrolist Plus — salida de audio (Bluetooth, audífonos, teléfono) al estilo del selector de dispositivos de Spotify.
 *
 * Android no deja que una app cambie el dispositivo Bluetooth activo por su cuenta; lo que sí permite es
 * abrir el selector de salida del sistema, que lista los dispositivos conectados y deja cambiar entre ellos.
 */

package com.metrolist.music.plus.player

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter2
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

enum class AudioOutputKind { PHONE, BLUETOOTH, HEADPHONES }

/** [name] is null when the UI should show its own label ("This phone", "Headphones"). */
data class AudioOutputInfo(val name: String?, val kind: AudioOutputKind)

private val BLUETOOTH_TYPES = setOf(
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_BLE_BROADCAST,
    AudioDeviceInfo.TYPE_HEARING_AID,
)

private val WIRED_TYPES = setOf(
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_DEVICE,
)

fun Context.openAudioOutputPicker() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val shown = runCatching { MediaRouter2.getInstance(this).showSystemOutputSwitcher() }.getOrDefault(false)
        if (shown) return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val sent = runCatching {
            sendBroadcast(
                Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG")
                    .setPackage("com.android.systemui")
                    .putExtra("package_name", packageName),
            )
        }.isSuccess
        if (sent) return
    }
    openBluetoothSettings()
}

fun Context.openBluetoothSettings() {
    runCatching {
        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun Context.selectedSystemRouteName(): String? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching {
            MediaRouter2.getInstance(this).systemController.selectedRoutes.firstOrNull()?.name?.toString()
        }.getOrNull()?.takeIf { it.isNotBlank() }
    } else {
        null
    }

private fun Context.currentAudioOutput(): AudioOutputInfo {
    val outputs = runCatching {
        getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.toList()
    }.getOrNull().orEmpty()

    val bluetooth = outputs.firstOrNull { it.type in BLUETOOTH_TYPES }
    if (bluetooth != null) {
        // With several Bluetooth devices connected, the selected system route tells which one is playing.
        val name = selectedSystemRouteName() ?: bluetooth.productName?.toString()
        return AudioOutputInfo(name, AudioOutputKind.BLUETOOTH)
    }
    if (outputs.any { it.type in WIRED_TYPES }) {
        return AudioOutputInfo(null, AudioOutputKind.HEADPHONES)
    }
    return AudioOutputInfo(null, AudioOutputKind.PHONE)
}

@Composable
fun rememberAudioOutput(): AudioOutputInfo {
    val context = LocalContext.current
    var info by remember { mutableStateOf(context.currentAudioOutput()) }

    DisposableEffect(context) {
        val audioManager = context.getSystemService(AudioManager::class.java)
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                info = context.currentAudioOutput()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                info = context.currentAudioOutput()
            }
        }
        audioManager?.registerAudioDeviceCallback(callback, null)
        onDispose { audioManager?.unregisterAudioDeviceCallback(callback) }
    }

    // Switching between already-connected devices does not fire the callback, so refresh periodically.
    LaunchedEffect(context) {
        while (true) {
            delay(3_000)
            info = context.currentAudioOutput()
        }
    }

    return info
}
