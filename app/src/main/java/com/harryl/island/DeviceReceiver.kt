package com.harryl.island

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Tracks the hardware the island reacts to. Everything is event driven, nothing polls:
 *  - Bluetooth audio via profile broadcasts, seeded from the profiles' real connected-device lists,
 *  - wired / USB-C earphones via AudioDeviceCallback (the X7 Max has no 3.5 mm jack, so USB-C
 *    earphones and dongles never send the old headset-plug broadcast),
 *  - charger and screen state.
 */
class DeviceReceiver(
    private val context: Context,
    private val prefs: Prefs,
    private val sink: (IslandEvent) -> Unit,
) : BroadcastReceiver() {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var registered = false

    /** AudioDeviceInfo ids of wired outputs currently plugged in. */
    private val wiredIds = HashSet<Int>()
    private var audioScanDone = false

    private val audioCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            // The first call after registering lists what is already plugged in.
            val announce = audioScanDone
            audioScanDone = true
            for (d in addedDevices) {
                if (!isWired(d)) continue
                val wasEmpty = wiredIds.isEmpty()
                wiredIds += d.id
                if (wasEmpty) emitWired(d, connected = true, announce = announce)
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            for (d in removedDevices) {
                if (wiredIds.remove(d.id) && wiredIds.isEmpty()) emitWired(d, connected = false, announce = true)
            }
        }
    }

    fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(ACTION_BT_BATTERY_LEVEL_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(context, this, filter, ContextCompat.RECEIVER_EXPORTED)
        audio.registerAudioDeviceCallback(audioCallback, main)
        registered = true
        seedBluetooth()
    }

    fun unregister() {
        if (!registered) return
        runCatching { context.unregisterReceiver(this) }
        runCatching { audio.unregisterAudioDeviceCallback(audioCallback) }
        registered = false
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> onBtProfile(intent)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> device(intent)?.let { emitBt(it, connected = false) }
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) sink(IslandEvent.BluetoothOff)
            }
            ACTION_BT_BATTERY_LEVEL_CHANGED -> onBtBattery(intent)
            Intent.ACTION_POWER_CONNECTED -> onPower(true)
            Intent.ACTION_POWER_DISCONNECTED -> onPower(false)
            Intent.ACTION_SCREEN_ON -> sink(IslandEvent.Screen(true))
            Intent.ACTION_SCREEN_OFF -> sink(IslandEvent.Screen(false))
        }
    }

    // ---- Bluetooth ----------------------------------------------------------------------------

    /** Ask the A2DP and headset profiles which devices are really connected right now. */
    fun seedBluetooth() {
        if (!hasBtPermission()) return
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val enabled = runCatching { adapter.isEnabled }.getOrDefault(false)
        if (!enabled) {
            sink(IslandEvent.BluetoothOff)
            return
        }
        for (profile in intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
            runCatching {
                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        try {
                            for (d in proxy.connectedDevices) {
                                sink(IslandEvent.DeviceState(describe(d, connected = true), announce = false))
                            }
                        } catch (_: SecurityException) {
                        } finally {
                            runCatching { adapter.closeProfileProxy(p, proxy) }
                        }
                    }

                    override fun onServiceDisconnected(p: Int) = Unit
                }, profile)
            }
        }
    }

    private fun onBtProfile(intent: Intent) {
        val device = device(intent) ?: return
        when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
            BluetoothProfile.STATE_CONNECTED -> emitBt(device, connected = true)
            BluetoothProfile.STATE_DISCONNECTED -> emitBt(device, connected = false)
        }
    }

    private fun onBtBattery(intent: Intent) {
        val device = device(intent) ?: return
        val level = intent.getIntExtra(EXTRA_BT_BATTERY_LEVEL, -1)
        if (level in 0..100) sink(IslandEvent.DeviceBattery(device.address, level))
    }

    private fun emitBt(device: BluetoothDevice, connected: Boolean) {
        if (connected && !isAudioDevice(device)) return
        // The service only announces real changes, so A2DP + HFP firing together shows once.
        sink(IslandEvent.DeviceState(describe(device, connected), announce = true))
    }

    private fun describe(device: BluetoothDevice, connected: Boolean): IslandContent.Device {
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
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private fun hasBtPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun isAudioDevice(device: BluetoothDevice): Boolean {
        val major = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull() ?: return true
        return major == BluetoothClass.Device.Major.AUDIO_VIDEO || major == BluetoothClass.Device.Major.WEARABLE ||
            major == BluetoothClass.Device.Major.UNCATEGORIZED
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
     * HFP headsets populate. It is reachable through reflection on Android 10-14. Returns -1 when unknown.
     */
    private fun readBattery(device: BluetoothDevice): Int = try {
        val m = device.javaClass.getMethod("getBatteryLevel")
        (m.invoke(device) as? Int)?.takeIf { it in 0..100 } ?: -1
    } catch (_: Throwable) {
        -1
    }

    // ---- Wired / USB-C ------------------------------------------------------------------------

    private fun isWired(d: AudioDeviceInfo): Boolean = d.isSink && when (d.type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE -> true
        else -> false
    }

    private fun emitWired(d: AudioDeviceInfo, connected: Boolean, announce: Boolean) {
        val usb = d.type == AudioDeviceInfo.TYPE_USB_HEADSET || d.type == AudioDeviceInfo.TYPE_USB_DEVICE
        // Analog headsets report the phone model as their product name, so only trust USB names.
        val name = d.productName?.toString()?.trim()?.takeIf { usb && it.isNotEmpty() }
            ?: context.getString(R.string.wired_headphones)
        sink(
            IslandEvent.DeviceState(
                IslandContent.Device(address = WIRED_ADDRESS, kind = DeviceKind.WIRED, name = name, battery = -1, connected = connected),
                announce = announce,
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
        const val WIRED_ADDRESS = "wired"
        const val ACTION_BT_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val EXTRA_BT_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
    }
}
