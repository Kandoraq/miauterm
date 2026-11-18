package org.cortex.terminal

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import org.cortex.terminal.runtime.DebianBackup
import org.cortex.terminal.runtime.DebianOperationRunner
import org.cortex.terminal.runtime.DebianRuntime
import org.cortex.terminal.runtime.Environment
import org.cortex.terminal.service.CortexService
import org.cortex.terminal.view.ExtraKeysConfig
import org.cortex.terminal.view.ExtraKeysLayout
import java.io.File

class SettingsActivity : AppCompatActivity() {
    private var operationDialog: AlertDialog? = null
    private val extraKeysExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Miauterm-ExtraKeysSettings").apply { isDaemon = true }
    }

    private val operationListener: (DebianOperationRunner.State) -> Unit = { state ->
        runOnUiThread {
            if (!isFinishing && !isDestroyed) handleOperationState(state)
        }
    }

    internal val backupPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let {
            runDebianOperation(getString(R.string.debian_backup_done), closeSettings = false) { context ->
                DebianBackup.export(context, it)
            }
        }
    }
    internal val restorePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { confirmRestore(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val container = findViewById<android.view.View>(R.id.settings_container)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            view.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)

        org.cortex.terminal.emulator.TerminalColor.applyTheme("miauterm")
        org.cortex.terminal.view.Chrome.apply(
            org.cortex.terminal.emulator.TerminalColor.SCHEMES.getValue(org.cortex.terminal.emulator.TerminalColor.currentThemeId)
        )
        val p = org.cortex.terminal.view.Chrome.palette
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(p.bg))
        findViewById<android.view.View>(R.id.settings_root).setBackgroundColor(p.bg)
        supportActionBar?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(p.bar))
        supportActionBar?.title = android.text.SpannableString(getString(R.string.settings_title)).apply {
            setSpan(android.text.style.ForegroundColorSpan(p.text), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        androidx.appcompat.content.res.AppCompatResources.getDrawable(this, R.drawable.ic_arrow_back)?.let {
            supportActionBar?.setHomeAsUpIndicator(it.mutate().apply { setTint(p.text) })
        }
        window.statusBarColor = p.bar
        window.navigationBarColor = p.bar
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !p.dark
        controller.isAppearanceLightNavigationBars = !p.dark

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    internal fun showProotLog() {
        if (isFinishing || isDestroyed) return
        val log = DebianRuntime.prootLog(this)
        try {
            extraKeysExecutor.execute {
                val text = if (log.isFile) {
                    runCatching { log.readText().takeLast(32 * 1024) }.getOrDefault("")
                } else {
                    ""
                }
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) showProotLogText(text)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    private fun showProotLogText(text: String) {
        val body = android.widget.TextView(this).apply {
            setTextColor(org.cortex.terminal.view.Chrome.palette.text)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(18, 14, 18, 14)
            this.text = text.ifBlank { getString(R.string.proot_log_empty) }
        }
        val scroll = android.widget.ScrollView(this).apply { addView(body) }
        org.cortex.terminal.view.ChromeDialog.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.pref_view_proot_log_title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .create()
        ).show()
    }

    internal fun confirmReset() {
        org.cortex.terminal.view.ChromeDialog.style(
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.debian_reset_confirm_title, DebianRuntime.systemName(this) ?: getString(R.string.pref_cat_debian)))
                .setMessage(R.string.debian_reset_confirm_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.pref_reset_debian_title) { _, _ ->
                    runDebianOperation(getString(R.string.debian_reset_done), closeSettings = true) { context ->
                        stopSessionsAndForget(context)
                        check(DebianRuntime.install(context, force = true)) {
                            context.getString(R.string.debian_install_failed)
                        }
                    }
                }
                .create()
        ).show()
    }

    internal fun confirmRemove() {
        org.cortex.terminal.view.ChromeDialog.style(
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.rootfs_remove_confirm_title, DebianRuntime.systemName(this) ?: "Debian"))
                .setMessage(R.string.rootfs_remove_confirm_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.rootfs_remove_title) { _, _ ->
                    runDebianOperation(getString(R.string.rootfs_remove_done), closeSettings = true) { context ->
                        stopSessionsAndForget(context)
                        DebianRuntime.removeDebian(context)
                    }
                }
                .create()
        ).show()
    }

    private fun confirmRestore(uri: Uri) {
        org.cortex.terminal.view.ChromeDialog.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.debian_restore_confirm_title)
                .setMessage(R.string.debian_restore_confirm_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.pref_restore_debian_title) { _, _ ->
                    runDebianOperation(getString(R.string.debian_restore_done), closeSettings = true) { context ->
                        stopSessionsAndForget(context)
                        DebianBackup.import(context, uri)
                    }
                }
                .create()
        ).show()
    }

    private fun stopSessionsAndForget(context: android.content.Context) {
        CortexService.getOrCreateSessionManager(context)
            .destroyAll(forget = true, waitForProcesses = true, notify = false)
    }

    private fun runDebianOperation(
        successMessage: String,
        closeSettings: Boolean,
        operation: (android.content.Context) -> Unit
    ) {
        if (!DebianOperationRunner.start(this, successMessage, closeSettings, operation)) {
            android.widget.Toast.makeText(this, R.string.debian_operation_running, android.widget.Toast.LENGTH_SHORT).show()
            showOperationDialog()
        }
    }

    private fun handleOperationState(state: DebianOperationRunner.State) {
        when (state) {
            DebianOperationRunner.State.Running -> showOperationDialog()
            is DebianOperationRunner.State.Finished -> {
                operationDialog?.dismiss()
                operationDialog = null
                if (state.success) {
                    org.cortex.terminal.view.ChromeDialog.style(
                        AlertDialog.Builder(this)
                            .setTitle(R.string.debian_operation_success)
                            .setMessage(state.message.orEmpty())
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                if (state.closeSettings) {
                                    finish()
                                } else if (DebianRuntime.isRemoved(this)) {
                                    recreate()
                                }
                            }
                            .create()
                    ).show()
                } else {
                    android.widget.Toast.makeText(
                        this,
                        getString(R.string.debian_operation_failed, state.message ?: getString(R.string.operation_failed_unknown)),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
            DebianOperationRunner.State.Idle -> {
                operationDialog?.dismiss()
                operationDialog = null
            }
        }
    }

    private fun showOperationDialog() {
        if (operationDialog?.isShowing == true) return
        operationDialog = org.cortex.terminal.view.ChromeDialog.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.debian_operation_running)
                .setView(android.widget.ProgressBar(this).apply { isIndeterminate = true })
                .setCancelable(false)
                .create()
        ).also { it.show() }
    }

    internal fun editExtraKeys() {
        val file = File(Environment.getHomeDir(this), ".config/miauterm/${ExtraKeysConfig.FILE_NAME}")
        try {
            extraKeysExecutor.execute {
                val initial = runCatching { if (file.isFile) file.readText() else ExtraKeysLayout.DEFAULT_TEXT + "\n" }
                    .getOrDefault(ExtraKeysLayout.DEFAULT_TEXT + "\n")
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) showExtraKeysDialog(file, initial)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    private fun showExtraKeysDialog(file: File, initial: String) {
        val parsed = ExtraKeysLayout.parse(initial)
        if (parsed.error != null) {
            android.widget.Toast.makeText(this, getString(R.string.extra_keys_invalid, parsed.error), android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val specials = ExtraKeysLayout.Special.values().filter { it != ExtraKeysLayout.Special.FIND }
        val selected = specials.map { special ->
            parsed.rows.flatten().any { it is ExtraKeysLayout.SpecialKey && it.special == special }
        }.toBooleanArray()
        val density = resources.displayMetrics.density
        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), 0, (20 * density).toInt(), 0)
        }
        content.addView(android.widget.TextView(this).apply {
            text = getString(R.string.extra_keys_guest_config)
            textSize = 13f
            setTextColor(org.cortex.terminal.view.Chrome.palette.textDim)
            setPadding(0, 0, 0, (12 * density).toInt())
        })
        val scroll = android.widget.ScrollView(this).apply { isFillViewport = true }
        val grid = android.widget.GridLayout(this).apply { columnCount = 2; useDefaultMargins = false }
        specials.forEachIndexed { index, special ->
            val check = android.widget.CheckBox(this).apply {
                text = if (special.name == special.label) special.name else "${special.name}  ${special.label}"
                isChecked = selected[index]
                minHeight = (48 * density).toInt()
                setTextColor(org.cortex.terminal.view.Chrome.palette.text)
                buttonTintList = android.content.res.ColorStateList.valueOf(org.cortex.terminal.view.Chrome.palette.accent)
                tag = index
            }
            grid.addView(check, android.widget.GridLayout.LayoutParams().apply {
                rowSpec = android.widget.GridLayout.spec(index / 2)
                columnSpec = android.widget.GridLayout.spec(index % 2, 1f)
                width = 0
                height = android.widget.GridLayout.LayoutParams.WRAP_CONTENT
                setMargins(0, 0, (4 * density).toInt(), 0)
            })
        }
        scroll.addView(grid)
        content.addView(scroll, android.widget.LinearLayout.LayoutParams(-1, (360 * density).toInt()))
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.pref_extra_keys_title)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.reset, null)
            .setPositiveButton(R.string.save, null)
            .create()
        org.cortex.terminal.view.ChromeDialog.style(dialog) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val enabled = (0 until grid.childCount).associate { index ->
                    val child = grid.getChildAt(index) as android.widget.CheckBox
                    (child.tag as Int) to child.isChecked
                }
                val textKeys = parsed.rows.flatten().filterIsInstance<ExtraKeysLayout.TextKey>()
                val selectedKeys = specials.filterIndexed { index, _ -> enabled[index] == true }
                    .map { ExtraKeysLayout.SpecialKey(it) }
                val keys: List<ExtraKeysLayout.Key> = selectedKeys + textKeys
                if (keys.isEmpty()) {
                    android.widget.Toast.makeText(this, R.string.extra_keys_select_one, android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val rowCount = if (keys.size > 1) 2 else 1
                val split = if (rowCount == 2) (keys.size + 1) / 2 else keys.size
                val rows = if (rowCount == 2) listOf(keys.take(split), keys.drop(split)) else listOf(keys)
                val text = rows.joinToString(",\n  ", "[\n  ", "\n]\n") { row ->
                    row.joinToString(", ", "[", "]") { key ->
                        val value = when (key) {
                            is ExtraKeysLayout.SpecialKey -> key.special.name
                            is ExtraKeysLayout.TextKey -> key.text
                        }
                        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r").replace("\u001b", "\\e")}\""
                    }
                }
                extraKeysExecutor.execute {
                    val saved = runCatching { ExtraKeysConfig.writeLayout(file, text) }
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        saved.onSuccess {
                            MainActivity.instance?.reloadExtraKeys()
                            dialog.dismiss()
                            android.widget.Toast.makeText(this, R.string.extra_keys_saved, android.widget.Toast.LENGTH_SHORT).show()
                        }.onFailure {
                            android.widget.Toast.makeText(
                                this,
                                getString(R.string.extra_keys_invalid, it.message ?: "write failed"),
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }
        org.cortex.terminal.view.ChromeDialog.style(dialog)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            try {
                extraKeysExecutor.execute {
                    runCatching { ExtraKeysConfig.writeLayout(file, ExtraKeysLayout.DEFAULT_TEXT) }
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        MainActivity.instance?.reloadExtraKeys()
                        dialog.dismiss()
                        android.widget.Toast.makeText(this, R.string.extra_keys_saved, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
            }
        }
        dialog.show()
    }

    internal fun showAbout() {
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
        val palette = org.cortex.terminal.view.Chrome.palette
        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24, 12, 24, 8)
            val iconSize = (72 * resources.displayMetrics.density).toInt()
            addView(android.widget.ImageView(this@SettingsActivity).apply {
                setImageResource(R.mipmap.ic_launcher)
                contentDescription = getString(R.string.app_name)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            }, android.widget.LinearLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            })
            addView(android.widget.TextView(this@SettingsActivity).apply {
                text = getString(R.string.about_description) + "\n" + getString(R.string.about_version, version)
                setTextColor(palette.text)
                textSize = 15f
                gravity = android.view.Gravity.CENTER
                setPadding(0, 14, 0, 14)
            })
            addView(android.widget.TextView(this@SettingsActivity).apply {
                text = getString(R.string.about_fork)
                setTextColor(palette.textDim)
                textSize = 13f
                gravity = android.view.Gravity.CENTER
            })
            addView(android.widget.TextView(this@SettingsActivity).apply {
                text = getString(R.string.about_open_source_notices)
                setTextColor(palette.accent)
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setPadding(0, 18, 0, 8)
                isClickable = true
                setOnClickListener { showOpenSourceNotices() }
            })
        }
        org.cortex.terminal.view.ChromeDialog.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.pref_about_title)
                .setView(content)
                .setPositiveButton(R.string.ok, null)
                .create()
        ).show()
    }

    private fun showOpenSourceNotices() {
        val notices = runCatching {
            assets.open("licenses/THIRD_PARTY_NOTICES.md").bufferedReader().use { it.readText() }
        }.getOrDefault(getString(R.string.about_notices_unavailable))
        val body = android.widget.TextView(this).apply {
            text = notices
            setTextColor(org.cortex.terminal.view.Chrome.palette.text)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(20, 16, 20, 16)
        }
        org.cortex.terminal.view.ChromeDialog.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.about_open_source_notices)
                .setView(android.widget.ScrollView(this).apply { addView(body) })
                .setPositiveButton(R.string.ok, null)
                .create()
        ).show()
    }

    override fun onStart() {
        super.onStart()
        DebianOperationRunner.addListener(operationListener)
    }

    override fun onStop() {
        DebianOperationRunner.removeListener(operationListener)
        super.onStop()
    }

    override fun onDestroy() {
        operationDialog?.dismiss()
        operationDialog = null
        extraKeysExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getBoolean("secure_screen", false)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private var enableSharingWhenReturningFromSystemSettings = false

        private val sharedFolderPermissionRequest = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            val granted = hasSharedFolderPermission()
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit().putBoolean(DebianRuntime.PREF_SHARED_FOLDER_ACCESS, granted).apply()
            updateSharedFolderPreference()
            android.widget.Toast.makeText(
                requireContext(),
                if (granted) R.string.shared_folder_access_granted_toast else R.string.shared_folder_access_denied_toast,
                android.widget.Toast.LENGTH_LONG
            ).show()
            if (!granted) {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.shared_folder_permission_denied_title)
                    .setMessage(R.string.shared_folder_permission_denied_message)
                    .setPositiveButton(R.string.open_settings) { _, _ ->
                        openStoragePermissionSettings(enableSharingOnReturn = true)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }

        override fun onResume() {
            super.onResume()
            if (enableSharingWhenReturningFromSystemSettings && hasSharedFolderPermission()) {
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .edit().putBoolean(DebianRuntime.PREF_SHARED_FOLDER_ACCESS, true).apply()
            }
            enableSharingWhenReturningFromSystemSettings = false
            updateSharedFolderPreference()
        }

        private fun hasSharedFolderPermission(): Boolean =
            DebianRuntime.hasSharedFolderPermission(requireContext())

        private fun updateSharedFolderPreference() {
            val preference = findPreference<SwitchPreferenceCompat>("shared_folder_access") ?: return
            val context = requireContext()
            val allowed = DebianRuntime.isSharedFolderAccessEnabled(context)
            preference.isChecked = allowed
            preference.summary = getString(
                if (allowed) R.string.shared_folder_access_enabled else R.string.shared_folder_access_disabled
            )
        }

        private fun openStoragePermissionSettings(enableSharingOnReturn: Boolean = false) {
            val context = requireContext()
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
            runCatching {
                startActivity(intent)
                enableSharingWhenReturningFromSystemSettings = enableSharingOnReturn
            }.onFailure {
                enableSharingWhenReturningFromSystemSettings = false
                android.widget.Toast.makeText(context, R.string.storage_permission_open_failed, android.widget.Toast.LENGTH_LONG).show()
            }
        }

        override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            SettingsStyle(this).install()
        }

        override fun onDisplayPreferenceDialog(preference: Preference) {
            if (preference is ListPreference) {

                showChoice(preference)
                return
            }
            super.onDisplayPreferenceDialog(preference)
        }

        private fun showChoice(pref: ListPreference) {
            val context = requireContext()
            val entries = pref.entries ?: return
            val values = pref.entryValues ?: return
            val p = org.cortex.terminal.view.Chrome.palette
            val checked = values.indexOfFirst { it.toString() == pref.value }
            val adapter = object : android.widget.ArrayAdapter<CharSequence>(
                context, android.R.layout.simple_list_item_single_choice, entries
            ) {
                override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                    val row = super.getView(position, convertView, parent) as android.widget.CheckedTextView
                    row.setTextColor(p.text)
                    row.checkMarkTintList = android.content.res.ColorStateList.valueOf(p.accent)
                    return row
                }
            }
            val dialog = androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(pref.dialogTitle ?: pref.title)
                .setSingleChoiceItems(adapter, checked) { d, which ->
                    val value = values[which].toString()
                    if (pref.callChangeListener(value)) pref.value = value
                    d.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            org.cortex.terminal.view.ChromeDialog.style(dialog).show()
        }

        private fun showRootfsFailsafeGuide() {
            val context = requireContext()
            val root = Environment.getCortexRoot(context).absolutePath
            val dialog = AlertDialog.Builder(context)
                .setTitle(R.string.rootfs_failsafe_guide_title)
                .setMessage(getString(R.string.rootfs_failsafe_guide_message, root))
                .setNegativeButton(R.string.close, null)
                .setPositiveButton(R.string.pref_failsafe_title) { _, _ ->
                    MainActivity.pendingFailsafeSession = true
                    activity?.finish()
                }
                .create()
            org.cortex.terminal.view.ChromeDialog.style(dialog).show()
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)
            updateSharedFolderPreference()
            val preferenceIcons = mapOf(
                "terminal_font" to R.drawable.ic_text_fields,
                "terminal_font_size" to R.drawable.ic_text_fields,
                "show_session_tabs" to R.drawable.ic_layers,
                "keep_screen_on" to R.drawable.ic_lightbulb,
                "secure_screen" to R.drawable.ic_shield,
                "restore_sessions" to R.drawable.ic_history,
                LocaleHelper.PREF_KEY to R.drawable.ic_language,
                "notify_long_commands" to R.drawable.ic_notifications,
                "long_command_threshold" to R.drawable.ic_schedule,
                "allow_osc52_explicit_opt_in" to R.drawable.ic_clipboard,
                "notify_servers" to R.drawable.ic_server,
                "shared_folder_access" to R.drawable.ic_folder_shared,
                "network_settings_toggle" to R.drawable.ic_server,
                "custom_dns_servers" to R.drawable.ic_server,
                "custom_hostname" to R.drawable.ic_person,
                DebianRuntime.PREF_LINUX_USER to R.drawable.ic_person,
                DebianRuntime.PREF_PROOT_DIAGNOSTICS to R.drawable.ic_tune,
                "view_proot_log" to R.drawable.ic_receipt,
                "clear_proot_log" to R.drawable.ic_delete,
                "failsafe_session" to R.drawable.ic_build,
                "rootfs_failsafe_guide" to R.drawable.ic_build,
                "backup_debian" to R.drawable.ic_backup,
                "restore_debian" to R.drawable.ic_restore,
                "reset_debian" to R.drawable.ic_restart,
                "remove_debian" to R.drawable.ic_delete,
                "edit_extra_keys" to R.drawable.ic_keyboard,
                "about_miauterm" to R.drawable.ic_info,
                "bug_report" to R.drawable.ic_bug_report
            )
            preferenceIcons.forEach { (key, icon) -> findPreference<Preference>(key)?.setIcon(icon) }
            val networkSettingsExpanded = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getBoolean("network_settings_expanded", false)
            val networkToggle = findPreference<Preference>("network_settings_toggle")
            val dnsPreference = findPreference<androidx.preference.EditTextPreference>("custom_dns_servers")
            val hostnamePreference = findPreference<androidx.preference.EditTextPreference>("custom_hostname")
            fun updateNetworkSettingsVisibility(expanded: Boolean) {
                dnsPreference?.isVisible = expanded
                hostnamePreference?.isVisible = expanded
                networkToggle?.summary = getString(
                    if (expanded) R.string.pref_network_settings_open else R.string.pref_network_settings_closed
                )
            }
            updateNetworkSettingsVisibility(networkSettingsExpanded)
            dnsPreference?.summaryProvider = Preference.SummaryProvider<androidx.preference.EditTextPreference> { pref ->
                pref.text?.takeIf { it.isNotBlank() } ?: getString(R.string.pref_custom_dns_summary)
            }
            hostnamePreference?.summaryProvider = Preference.SummaryProvider<androidx.preference.EditTextPreference> { pref ->
                pref.text?.takeIf { it.isNotBlank() } ?: getString(R.string.default_localhost)
            }
            networkToggle?.setOnPreferenceClickListener {
                val expanded = dnsPreference?.isVisible != true
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .edit().putBoolean("network_settings_expanded", expanded).apply()
                updateNetworkSettingsVisibility(expanded)
                true
            }
            dnsPreference?.setOnPreferenceChangeListener { _, newValue ->
                val valid = runCatching { DebianRuntime.parseCustomDnsServers(newValue as? String ?: "") }.isSuccess
                if (!valid) android.widget.Toast.makeText(requireContext(), R.string.pref_custom_dns_invalid, android.widget.Toast.LENGTH_LONG).show()
                valid
            }
            hostnamePreference?.setOnPreferenceChangeListener { _, newValue ->
                val valid = DebianRuntime.isValidHostname(newValue as? String ?: "")
                if (!valid) android.widget.Toast.makeText(requireContext(), R.string.pref_custom_hostname_invalid, android.widget.Toast.LENGTH_LONG).show()
                valid
            }
            findPreference<androidx.preference.Preference>("reset_network_settings")?.setOnPreferenceClickListener {
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .edit()
                    .remove(DebianRuntime.PREF_CUSTOM_DNS_SERVERS)
                    .remove(DebianRuntime.PREF_CUSTOM_HOSTNAME)
                    .apply()
                dnsPreference?.text = ""
                hostnamePreference?.text = ""
                android.widget.Toast.makeText(requireContext(), R.string.pref_reset_network_summary, android.widget.Toast.LENGTH_SHORT).show()
                true
            }

            findPreference<SwitchPreferenceCompat>("shared_folder_access")?.apply {
                isPersistent = false
                setOnPreferenceChangeListener { _, newValue ->
                    val requested = newValue as Boolean
                    val currentlyAllowed = hasSharedFolderPermission()
                    if (requested && !currentlyAllowed) {
                        sharedFolderPermissionRequest.launch(
                            arrayOf(
                                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                            )
                        )
                    } else {
                        androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
                            .edit().putBoolean(DebianRuntime.PREF_SHARED_FOLDER_ACCESS, requested).apply()
                        updateSharedFolderPreference()
                        if (!requested && currentlyAllowed) {
                            openStoragePermissionSettings()
                        }
                    }
                    false
                }
            }

            findPreference<ListPreference>(LocaleHelper.PREF_KEY)?.let { pref ->

                val available = resources.getStringArray(R.array.language_values).toList()
                pref.value = LocaleHelper.currentSelection(available)
                pref.setOnPreferenceChangeListener { _, newValue ->
                    LocaleHelper.setLanguage(newValue as String)
                    true
                }
            }

            findPreference<ListPreference>("terminal_font_size")?.summaryProvider =
                Preference.SummaryProvider<ListPreference> { pref -> pref.entry ?: pref.value?.let { "$it sp" } }

            findPreference<androidx.preference.SwitchPreferenceCompat>(DebianRuntime.PREF_PROOT_DIAGNOSTICS)
                ?.summary = getString(R.string.pref_proot_diagnostics_summary)

            findPreference<Preference>("view_proot_log")?.setOnPreferenceClickListener {
                (requireActivity() as SettingsActivity).showProotLog()
                true
            }
            findPreference<Preference>("clear_proot_log")?.setOnPreferenceClickListener {
                val ok = DebianRuntime.clearProotLog(requireContext())
                android.widget.Toast.makeText(
                    requireContext(),
                    if (ok) R.string.proot_log_cleared else R.string.proot_log_clear_failed,
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                true
            }
            findPreference<Preference>("failsafe_session")?.setOnPreferenceClickListener {
                MainActivity.pendingFailsafeSession = true
                activity?.finish()
                true
            }
            findPreference<Preference>("rootfs_failsafe_guide")?.setOnPreferenceClickListener {
                showRootfsFailsafeGuide()
                true
            }
            findPreference<Preference>("backup_debian")?.setOnPreferenceClickListener {
                (requireActivity() as SettingsActivity).backupPicker.launch(getString(R.string.debian_backup_filename))
                true
            }
            findPreference<Preference>("restore_debian")?.setOnPreferenceClickListener {
                (requireActivity() as SettingsActivity).restorePicker.launch(arrayOf("application/zip", "application/octet-stream"))
                true
            }
            findPreference<Preference>("reset_debian")?.setOnPreferenceClickListener {
                (requireActivity() as SettingsActivity).confirmReset()
                true
            }
            findPreference<Preference>("remove_debian")?.setOnPreferenceClickListener {
                (requireActivity() as SettingsActivity).confirmRemove()
                true
            }
            findPreference<androidx.preference.EditTextPreference>(DebianRuntime.PREF_LINUX_USER)?.let { pref ->
                pref.setOnPreferenceChangeListener { _, newValue ->
                    val requested = (newValue as? String).orEmpty().trim()
                    val accounts = DebianRuntime.accounts(Environment.getCortexRoot(requireContext()))
                    if (requested.isEmpty() || accounts.any { it.name == requested }) {
                        true
                    } else {
                        android.widget.Toast.makeText(
                            requireContext(),
                            getString(R.string.linux_user_not_found),
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        false
                    }
                }
            }
            val rootfsPresent = DebianRuntime.isInstalled(requireContext())
            findPreference<Preference>("backup_debian")?.isVisible = rootfsPresent
            findPreference<Preference>("restore_debian")?.isVisible = rootfsPresent
            val systemName = DebianRuntime.systemName(requireContext()) ?: "Linux system"
            findPreference<androidx.preference.PreferenceCategory>("linux_system_category")?.title = systemName
            findPreference<Preference>("reset_debian")?.apply {
                isVisible = rootfsPresent
                title = getString(R.string.rootfs_reset_title, systemName)
            }
            findPreference<Preference>("edit_extra_keys")?.setOnPreferenceClickListener {
                (requireActivity() as SettingsActivity).editExtraKeys()
                true
            }
            findPreference<Preference>("about_miauterm")?.setOnPreferenceClickListener {
                (requireActivity() as SettingsActivity).showAbout()
                true
            }

            findPreference<Preference>("bug_report")?.setOnPreferenceClickListener {
                runCatching {
                    val report = BugReport.write(requireContext())
                    BugReport.share(requireActivity(), report)
                }.onFailure {
                    android.widget.Toast.makeText(requireContext(), R.string.update_connect_failed, android.widget.Toast.LENGTH_LONG).show()
                }
                true
            }
        }
    }
}
