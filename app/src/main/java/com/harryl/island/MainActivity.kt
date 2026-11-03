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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.harryl.island.databinding.ActivityMainBinding
import com.harryl.island.databinding.ItemPermissionBinding
import kotlin.math.roundToInt

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
        ExpressiveList.style(b.permissionList)
        ExpressiveList.style(b.groupPosition)
        ExpressiveList.style(b.groupBehaviour)
        ExpressiveList.style(b.groupTips)
        // Edge to edge: keep the last row clear of the gesture bar.
        ViewCompat.setOnApplyWindowInsetsListener(b.scroll) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottom)
            insets
        }
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
        addRow(R.string.perm_a11y, R.string.perm_a11y_desc, { IslandAccessibilityService.isEnabled(this) }) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
        addRow(R.string.perm_overlay, R.string.perm_overlay_desc, { Settings.canDrawOverlays(this) }) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
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
        val canShow = IslandService.canShow(this)
        b.switchEnabled.setOnCheckedChangeListener(null)
        b.switchEnabled.isChecked = prefs.enabled && canShow
        b.switchEnabled.setOnCheckedChangeListener { _, checked -> setEnabled(checked) }
        updateHero()
        if (prefs.enabled && canShow) {
            if (IslandService.running) IslandService.resync(this) else IslandService.start(this)
        }
        updateDetection()
    }

    private fun setEnabled(on: Boolean) {
        if (on && !IslandService.canShow(this)) {
            Toast.makeText(this, R.string.need_host, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            b.switchEnabled.isChecked = false
            return
        }
        prefs.enabled = on
        updateHero()
        if (on) {
            IslandService.start(this)
            if (!IslandAccessibilityService.isEnabled(this)) Toast.makeText(this, R.string.taps_blocked, Toast.LENGTH_LONG).show()
        } else {
            IslandService.stop(this)
        }
    }

    private fun updateHero() {
        b.heroStatus.setText(
            when {
                !IslandService.canShow(this) -> R.string.hero_needs_setup
                prefs.enabled -> R.string.hero_on
                else -> R.string.hero_off
            },
        )
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
        b.switchMaterialYou.bind({ prefs.materialYou }) {
            prefs.materialYou = it
            IslandService.relayout(this)
        }
        b.switchMaterialYou.isEnabled = Build.VERSION.SDK_INT >= 31
        b.switchPersistentMusic.bind({ prefs.persistentMusic }) { prefs.persistentMusic = it }
        b.switchVisualizer.bind({ prefs.visualizer }) { prefs.visualizer = it }
        b.switchHaptics.bind({ prefs.haptics }) { prefs.haptics = it }
        b.switchNotifications.bind({ prefs.showNotifications }) { prefs.showNotifications = it }
        b.switchLive.bind({ prefs.showLive }) { prefs.showLive = it }
        b.switchShade.bind({ prefs.hideWithShade }) { prefs.hideWithShade = it }
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

    private fun MaterialSwitch.bind(get: () -> Boolean, set: (Boolean) -> Unit) {
        isChecked = get()
        setOnCheckedChangeListener { _, checked -> set(checked) }
    }

    // ---- Position -----------------------------------------------------------------------------

    private fun bindPosition() {
        b.btnCalibrate.setOnClickListener { startActivity(Intent(this, CalibrateActivity::class.java)) }
        b.anchorGroup.addOnButtonCheckedListener { _, id, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val anchor = when (id) {
                R.id.anchorCenter -> Prefs.ANCHOR_CENTER
                R.id.anchorCalibrated -> Prefs.ANCHOR_CALIBRATED
                else -> Prefs.ANCHOR_AUTO
            }
            if (anchor == prefs.anchor) return@addOnButtonCheckedListener
            prefs.anchor = anchor
            updateDetection()
            relayoutAndPreview()
        }
        b.offsetX.value = prefs.offsetX.toFloat().coerceIn(-20f, 20f)
        b.offsetY.value = prefs.offsetY.toFloat().coerceIn(-20f, 20f)
        b.offsetX.addOnChangeListener { _, v, fromUser -> prefs.offsetX = v.toInt(); if (fromUser) relayoutAndPreview() }
        b.offsetY.addOnChangeListener { _, v, fromUser -> prefs.offsetY = v.toInt(); if (fromUser) relayoutAndPreview() }
        b.btnResetNudge.setOnClickListener {
            b.offsetX.value = 0f
            b.offsetY.value = 0f
            prefs.offsetX = 0
            prefs.offsetY = 0
            relayoutAndPreview()
        }
    }

    /** Shows exactly what Android reports, so it's clear where the pill will sit. */
    private fun updateDetection() {
        // Reflect the saved choice; "My line-up" only exists once a line-up was saved.
        b.anchorCalibrated.isEnabled = prefs.calibrated
        val checked = when {
            prefs.anchor == Prefs.ANCHOR_CENTER -> R.id.anchorCenter
            prefs.anchor == Prefs.ANCHOR_CALIBRATED && prefs.calibrated -> R.id.anchorCalibrated
            else -> R.id.anchorAuto
        }
        if (b.anchorGroup.checkedButtonId != checked) b.anchorGroup.check(checked)

        val g = IslandGeometry.detect(this)
        val cam = IslandLayout.usableCamera(g.camera, g.screenW, g.screenH)
        val d = resources.displayMetrics.density
        fun pos(box: CameraBox): String {
            val cx = (box.left + box.right) / 2f
            return getString(
                when {
                    cx < g.screenW * 0.35f -> R.string.pos_left
                    cx > g.screenW * 0.65f -> R.string.pos_right
                    else -> R.string.pos_centre
                },
            )
        }
        b.detectedInfo.text = when {
            prefs.anchor == Prefs.ANCHOR_CENTER -> getString(R.string.detect_forced)
            cam == null -> getString(R.string.detect_none)
            g.source == IslandGeometry.Source.CALIBRATED ->
                getString(R.string.detect_calibrated, (cam.width / d).roundToInt(), pos(cam))
            g.source == IslandGeometry.Source.ESTIMATED && g.reported != null -> {
                val r = g.reported
                getString(R.string.detect_estimated, (r.width / d).roundToInt(), (r.height / d).roundToInt(), pos(r))
            }
            else -> {
                val source = getString(if (g.source == IslandGeometry.Source.CUTOUT_PATH) R.string.source_path else R.string.source_rect)
                getString(R.string.detect_camera, pos(cam), (cam.width / d).roundToInt(), (cam.height / d).roundToInt(), source)
            }
        }
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
            b.previewStopwatch to IslandService.PREVIEW_STOPWATCH,
        )
        for ((chip, kind) in map) chip.setOnClickListener { preview(kind) }
    }

    private fun preview(kind: String) {
        if (!prefs.enabled || !IslandService.canShow(this)) {
            Toast.makeText(this, R.string.preview_requires_enable, Toast.LENGTH_SHORT).show()
            return
        }
        IslandService.preview(this, kind)
    }
}
