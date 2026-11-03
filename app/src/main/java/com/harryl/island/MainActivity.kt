package com.harryl.island

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.harryl.island.databinding.ActivityMainBinding
import com.harryl.island.databinding.ItemPermissionBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private val permissionRows = ArrayList<Pair<ItemPermissionBinding, () -> Boolean>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)
        buildPermissionRows()
        bindSwitches()
        bindPosition()
        bindPreview()
        b.btnAppInfo.setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        b.btnBattery.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---- Permissions --------------------------------------------------------------------------

    private fun buildPermissionRows() {
        addRow(R.string.perm_overlay, R.string.perm_overlay_desc, { Settings.canDrawOverlays(this) }) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        addRow(R.string.perm_notifications, R.string.perm_notifications_desc, ::hasNotificationAccess) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        if (Build.VERSION.SDK_INT >= 31) {
            addRow(R.string.perm_bluetooth, R.string.perm_bluetooth_desc, { granted(Manifest.permission.BLUETOOTH_CONNECT) }) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            addRow(R.string.perm_post, R.string.perm_post_desc, { granted(Manifest.permission.POST_NOTIFICATIONS) }) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            }
        }
        addRow(R.string.perm_battery, R.string.perm_battery_desc, ::ignoringBatteryOptimizations) {
            runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }.onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun addRow(title: Int, desc: Int, check: () -> Boolean, grant: () -> Unit) {
        val row = ItemPermissionBinding.inflate(LayoutInflater.from(this), b.permissionList, true)
        row.permTitle.setText(title)
        row.permDesc.setText(desc)
        row.permButton.setOnClickListener { grant() }
        permissionRows += row to check
    }

    private fun refresh() {
        for ((row, check) in permissionRows) {
            val ok = check()
            row.permButton.isEnabled = !ok
            row.permButton.setText(if (ok) R.string.granted else R.string.grant)
        }
        b.switchEnabled.setOnCheckedChangeListener(null)
        b.switchEnabled.isChecked = prefs.enabled && Settings.canDrawOverlays(this)
        b.switchEnabled.setOnCheckedChangeListener { _, checked -> setEnabled(checked) }
        if (prefs.enabled && Settings.canDrawOverlays(this) && !IslandService.running) IslandService.start(this)
    }

    private fun setEnabled(on: Boolean) {
        if (on && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.perm_overlay, Toast.LENGTH_SHORT).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            b.switchEnabled.isChecked = false
            return
        }
        prefs.enabled = on
        if (on) IslandService.start(this) else IslandService.stop(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun hasNotificationAccess() = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    private fun ignoringBatteryOptimizations() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    // ---- Behaviour ----------------------------------------------------------------------------

    private fun bindSwitches() {
        b.switchPersistentMusic.bind({ prefs.persistentMusic }) { prefs.persistentMusic = it }
        b.switchVisualizer.bind({ prefs.visualizer }) { prefs.visualizer = it }
        b.switchHaptics.bind({ prefs.haptics }) { prefs.haptics = it }
        b.switchNotifications.bind({ prefs.showNotifications }) { prefs.showNotifications = it }
        b.switchDevices.bind({ prefs.showDevices }) { prefs.showDevices = it }
        b.switchCharging.bind({ prefs.showCharging }) { prefs.showCharging = it }

        b.collapseSeconds.value = prefs.collapseSeconds.toFloat().coerceIn(2f, 10f)
        updateCollapseLabel()
        b.collapseSeconds.addOnChangeListener { _, value, _ ->
            prefs.collapseSeconds = value.toInt()
            updateCollapseLabel()
        }
    }

    private fun updateCollapseLabel() {
        b.collapseLabel.text = getString(R.string.collapse_after) + ": " + prefs.collapseSeconds + " s"
    }

    private fun com.google.android.material.materialswitch.MaterialSwitch.bind(get: () -> Boolean, set: (Boolean) -> Unit) {
        isChecked = get()
        setOnCheckedChangeListener { _, checked -> set(checked) }
    }

    // ---- Position -----------------------------------------------------------------------------

    private fun bindPosition() {
        when (prefs.anchor) {
            Prefs.ANCHOR_LEFT -> b.anchorLeft.isChecked = true
            Prefs.ANCHOR_CENTER -> b.anchorCenter.isChecked = true
            else -> b.anchorAuto.isChecked = true
        }
        b.anchorGroup.setOnCheckedChangeListener { _, id ->
            prefs.anchor = when (id) {
                R.id.anchorLeft -> Prefs.ANCHOR_LEFT
                R.id.anchorCenter -> Prefs.ANCHOR_CENTER
                else -> Prefs.ANCHOR_AUTO
            }
            relayoutAndPreview()
        }
        b.offsetX.value = prefs.offsetX.toFloat().coerceIn(-40f, 40f)
        b.offsetY.value = prefs.offsetY.toFloat().coerceIn(-40f, 40f)
        b.offsetX.addOnChangeListener { _, v, fromUser -> prefs.offsetX = v.toInt(); if (fromUser) relayoutAndPreview() }
        b.offsetY.addOnChangeListener { _, v, fromUser -> prefs.offsetY = v.toInt(); if (fromUser) relayoutAndPreview() }
    }

    private fun relayoutAndPreview() {
        if (!IslandService.running) return
        IslandService.relayout(this)
        IslandService.preview(this, IslandService.PREVIEW_EARBUDS)
    }

    // ---- Preview ------------------------------------------------------------------------------

    private fun bindPreview() {
        val map = mapOf(
            b.previewMusic to IslandService.PREVIEW_MUSIC,
            b.previewEarbuds to IslandService.PREVIEW_EARBUDS,
            b.previewWired to IslandService.PREVIEW_WIRED,
            b.previewNotice to IslandService.PREVIEW_NOTICE,
            b.previewCharging to IslandService.PREVIEW_CHARGING,
        )
        for ((chip, kind) in map) {
            chip.setOnClickListener { preview(kind) }
        }
    }

    private fun preview(kind: String) {
        if (!prefs.enabled || !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.preview_requires_enable, Toast.LENGTH_SHORT).show()
            return
        }
        IslandService.preview(this, kind)
    }

    @Suppress("unused")
    private fun View.show(visible: Boolean) {
        visibility = if (visible) View.VISIBLE else View.GONE
    }
}
