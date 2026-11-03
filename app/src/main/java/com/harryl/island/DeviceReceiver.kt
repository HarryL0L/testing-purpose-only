package com.harryl.island

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * Listens for the hardware events the island reacts to. Everything is broadcast-driven,
 * nothing polls, so this costs nothing while idle.
 */
class DeviceReceiver(
    private val context: Context,
    private val prefs: Prefs,
    private val sink: (IslandEvent) -> Unit,
) : BroadcastReceiver() {

    private var registered = false
    /** address -> elapsedRealtime of the last connect/disconnect we surfaced, to merge A2DP + HFP events. */
    private val lastShown = HashMap<String, Long>()
    private var lastWiredState = -1

    fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(ACTION_BT_BATTERY_LEVEL_CHANGED)
            addAction(AudioManager.ACTION_HEADSET_PLUG)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(context, this, filter, ContextCompat.RECEIVER_EXPORTED)
        registered = true
    }

    fun unregister() {
        if (!registered) return
        runCatching { context.unregisterReceiver(this) }
        registered = false
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> onBtProfile(intent)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> onBtDisconnected(intent)
            ACTION_BT_BATTERY_LEVEL_CHANGED -> onBtBattery(intent)
            AudioManager.ACTION_HEADSET_PLUG -> onWired(intent)
            Intent.ACTION_POWER_CONNECTED -> onPower(true)
            Intent.ACTION_POWER_DISCONNECTED -> onPower(false)
            Intent.ACTION_SCREEN_ON -> sink(IslandEvent.Screen(true))
            Intent.ACTION_SCREEN_OFF -> sink(IslandEvent.Screen(false))
        }
    }

    // ---- Bluetooth ----------------------------------------------------------------------------

    private fun onBtProfile(intent: Intent) {
        val device = device(intent) ?: return
        when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
            BluetoothProfile.STATE_CONNECTED -> surface(device, connected = true)
            BluetoothProfile.STATE_DISCONNECTED -> surface(device, connected = false)
        }
    }

    private fun onBtDisconnected(intent: Intent) {
        val device = device(intent) ?: return
        if (!isAudioDevice(device)) return
        surface(device, connected = false)
    }

    private fun onBtBattery(intent: Intent) {
        val device = device(intent) ?: return
        val level = intent.getIntExtra(EXTRA_BT_BATTERY_LEVEL, -1)
        if (level in 0..100) sink(IslandEvent.DeviceBattery(device.address, level))
    }

    private fun surface(device: BluetoothDevice, connected: Boolean) {
        if (!prefs.showDevices) return
        val now = SystemClock.elapsedRealtime()
        val key = device.address + (if (connected) "+" else "-")
        val last = lastShown[key] ?: 0L
        if (now - last < 4_000) return // A2DP and HFP both fire; show once.
        lastShown[key] = now
        sink(IslandEvent.Transient(describe(device, connected)))
    }

    fun describe(device: BluetoothDevice, connected: Boolean): IslandContent.Device {
        val name = runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.unknown_device)
        return IslandContent.Device(
            address = device.address,
            kind = kindOf(device, name),
            name = name,
            battery = if (connected) readBattery(device) else -1,
            connected = connected,
        )
    }

    private fun device(intent: Intent): BluetoothDevice? =
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    private fun isAudioDevice(device: BluetoothDevice): Boolean {
        val major = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull() ?: return true
        return major == BluetoothClass.Device.Major.AUDIO_VIDEO || major == BluetoothClass.Device.Major.WEARABLE
    }

    private fun kindOf(device: BluetoothDevice, name: String): DeviceKind {
        val lower = name.lowercase()
        if (lower.contains("wf-") || lower.contains("buds") || lower.contains("pods") || lower.contains("dots")) {
            return DeviceKind.EARBUDS
        }
        if (lower.contains("watch") || lower.contains("band")) return DeviceKind.WATCH
        val cls = runCatching { device.bluetoothClass?.deviceClass }.getOrNull() ?: return DeviceKind.HEADPHONES
        return when (cls) {
            BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
            BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> DeviceKind.EARBUDS
            BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES -> DeviceKind.HEADPHONES
            BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
            BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,
            BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO -> DeviceKind.SPEAKER
            BluetoothClass.Device.WEARABLE_WRIST_WATCH -> DeviceKind.WATCH
            else -> DeviceKind.OTHER
        }
    }

    /**
     * BluetoothDevice.getBatteryLevel() is a hidden API that Sony (WF-C500), OnePlus/Realme and most
     * HFP headsets populate via AT+IPHONEACCEV / AT+XAPL. It is reachable through reflection on
     * Android 10-14. Returns -1 when unknown.
     */
    private fun readBattery(device: BluetoothDevice): Int = try {
        val m = device.javaClass.getMethod("getBatteryLevel")
        (m.invoke(device) as? Int)?.takeIf { it in 0..100 } ?: -1
    } catch (_: Throwable) {
        -1
    }

    // ---- Wired --------------------------------------------------------------------------------

    private fun onWired(intent: Intent) {
        val state = intent.getIntExtra("state", -1)
        if (state !in 0..1) return
        // The broadcast is sticky: the first delivery after registering is just the current state.
        if (isInitialStickyBroadcast || lastWiredState == -1 && state == 0) {
            lastWiredState = state
            return
        }
        if (state == lastWiredState) return
        lastWiredState = state
        if (!prefs.showDevices) return
        val name = intent.getStringExtra("name")?.takeIf { it.isNotBlank() && !it.equals("h2w", true) }
            ?: context.getString(R.string.wired_headphones)
        sink(
            IslandEvent.Transient(
                IslandContent.Device(address = "wired", kind = DeviceKind.WIRED, name = name, battery = -1, connected = state == 1),
            ),
        )
    }

    // ---- Charger ------------------------------------------------------------------------------

    private fun onPower(connected: Boolean) {
        if (!prefs.showCharging) return
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        sink(IslandEvent.Transient(IslandContent.Power(pct, connected)))
    }

    companion object {
        const val ACTION_BT_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val EXTRA_BT_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
    }
}
