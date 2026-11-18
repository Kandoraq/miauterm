package org.cortex.terminal

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.WindowCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.cortex.terminal.databinding.ActivityMainBinding
import org.cortex.terminal.runtime.DebianRuntime
import org.cortex.terminal.runtime.Environment
import org.cortex.terminal.runtime.MiautermCommandInstaller
import org.cortex.terminal.service.CortexService
import org.cortex.terminal.session.SessionAdapter
import org.cortex.terminal.session.SessionManager
import org.cortex.terminal.session.TerminalSession
import org.cortex.terminal.view.Chrome
import org.cortex.terminal.view.ChromeDialog
import org.cortex.terminal.view.ExtraKeysConfig
import org.cortex.terminal.view.ExtraKeysLayout
import org.cortex.terminal.view.ExtraKeysView
import org.cortex.terminal.view.SessionTabsView
import org.cortex.terminal.view.TerminalView
import org.cortex.terminal.view.showInChrome

class MainActivity : AppCompatActivity() {

    companion object {
        @Volatile
        var instance: MainActivity? = null
            private set

        @Volatile
        var pendingSessionCommand: String? = null
        @Volatile
        var pendingFailsafeSession = false

        private val bootstrapLock = Any()
        private var bootstrapInProgress = false
        private const val REQUEST_POST_NOTIFICATIONS = 1001

    }

    private lateinit var binding: ActivityMainBinding

    private val drawerLayout: DrawerLayout get() = binding.drawerLayout
    private val terminalView: TerminalView get() = binding.terminalView
    private val extraKeysView: ExtraKeysView get() = binding.extraKeysView
    private val btnDrawerSettings: ImageView get() = binding.btnDrawerSettings
    private val btnDrawerFiles: ImageView get() = binding.btnDrawerFiles

    private val sessionRecyclerView: RecyclerView get() = binding.sessionRecyclerView
    private lateinit var sessionAdapter: SessionAdapter
    private val btnDrawerKeyboard: View get() = binding.btnDrawerKeyboard
    private val btnDrawerNewSession: View get() = binding.btnDrawerNewSession

    private lateinit var sessionManager: SessionManager
    private lateinit var extraKeysConfig: ExtraKeysConfig
    private val applyExtraKeysRunnable = Runnable { applyExtraKeys() }
    private var isBootstrapping = false
    private var runtimeInitializationStarted = false
    private var waitingForNotificationPrompt = false
    private var notificationPromptInterruptedActivity = false
    private var bootstrapDialog: BootstrapDialog? = null
    @Volatile
    private var bootstrapCancelled = false
    private var removedRootfsNoticeShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        DebianRuntime.reinstallConfirmation = { confirmRootfsReinstall() }
        extraKeysConfig = ExtraKeysConfig(Environment.getHomeDir(this))

        sessionManager = CortexService.getOrCreateSessionManager(this)

        setupBackPressedDispatcher()
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
        try {
            val mLeftDraggerField = DrawerLayout::class.java.getDeclaredField("mLeftDragger")
            mLeftDraggerField.isAccessible = true
            val leftDragger = mLeftDraggerField.get(drawerLayout)
            val edgeSizeField = leftDragger.javaClass.getDeclaredField("mEdgeSize")
            edgeSizeField.isAccessible = true
            val density = resources.displayMetrics.density
            edgeSizeField.setInt(leftDragger, (TerminalView.EDGE_SLOP_DP * density).toInt())
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Could not widen the drawer edge", e)
        }

        terminalView.onTerminalTouchWhenDrawerOpen = {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
                true
            } else {
                false
            }
        }

        terminalView.onTextSizeChangedByPinch = { sp ->
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putString("terminal_font_size", sp.toInt().toString())
                .apply()
        }

        extraKeysView.terminalView = terminalView
        terminalView.onSessionChanged = { extraKeysView.clearLatch() }
        extraKeysView.onMenuClick = {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                drawerLayout.openDrawer(GravityCompat.START)
            }
        }

        terminalView.onUrlTapped = { url ->
            try {
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                if (clipboard != null) {
                    val clip = android.content.ClipData.newPlainText("URL", url)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this, getString(R.string.copied_url, url), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, url, Toast.LENGTH_SHORT).show()
            }
        }

        btnDrawerFiles.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, DebianFilesActivity::class.java))
        }

        btnDrawerSettings.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        instance = this
        requestNotificationsThenInitialize()
    }

    private fun requestNotificationsThenInitialize() {
        if (runtimeInitializationStarted) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            val manager = getSystemService(android.app.NotificationManager::class.java)
            if (manager.getNotificationChannel(CortexService.CHANNEL_ID) == null) {
                waitingForNotificationPrompt = true
                manager.createNotificationChannel(
                    android.app.NotificationChannel(
                        CortexService.CHANNEL_ID,
                        getString(R.string.notif_channel_name),
                        android.app.NotificationManager.IMPORTANCE_LOW
                    ).apply { description = getString(R.string.notif_channel_description) }
                )

                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_POST_NOTIFICATIONS
                )
                return
            }
        }
        initializeRuntime()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_POST_NOTIFICATIONS) {
            waitingForNotificationPrompt = false
            notificationPromptInterruptedActivity = false
            if (!isFinishing && !isDestroyed) initializeRuntime()
        }
    }

    private fun initializeRuntime() {
        if (runtimeInitializationStarted || isFinishing || isDestroyed) return
        runtimeInitializationStarted = true

        try {
            CortexService.start(this)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "CortexService.start failed", e)
        }
        sessionManager = CortexService.getOrCreateSessionManager(this)

        sessionAdapter = SessionAdapter(
            sessionManager = sessionManager,
            onSelect = { index ->
                sessionManager.switchTo(index)
                drawerLayout.closeDrawer(GravityCompat.START)
            },
            onClose = { index -> closeSession(index) },
            onRename = { index -> showRenameSessionDialog(index) }
        )

        sessionRecyclerView.layoutManager = LinearLayoutManager(this)
        sessionRecyclerView.adapter = sessionAdapter

        binding.sessionTabs.onSelect = { index -> sessionManager.switchTo(index) }
        binding.sessionTabs.onClose = { index -> closeSession(index) }
        binding.sessionTabs.onNew = { createNewSession() }
        binding.sessionTabs.onRename = { index -> showRenameSessionDialog(index) }

        drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            private var shown = false
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                if (slideOffset > 0f && !shown) {
                    shown = true
                    sessionAdapter.notifyDataSetChanged()
                } else if (slideOffset == 0f) {
                    shown = false
                }
            }
        })

        sessionManager.onSessionChanged = { session ->
            runOnUiThread {
                if (session == null) {
                    if (!isBootstrapping && !isFinishing && !isDestroyed) {
                        CortexService.stop(this)
                        finish()
                    }
                } else {
                    CortexService.updateNotification(this)
                    terminalView.session = session
                    sessionAdapter.notifyDataSetChanged()
                    refreshTabs()
                    terminalView.invalidate()
                }
            }
        }

        btnDrawerKeyboard.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            terminalView.showKeyboard()
        }

        btnDrawerNewSession.setOnClickListener {
            createNewSession()
            drawerLayout.closeDrawer(GravityCompat.START)
        }
        btnDrawerNewSession.setOnLongClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            showNewSessionDialog()
            true
        }

        applyPreferences()
        val systemReady = !pendingFailsafeSession && DebianRuntime.isInstalled(this)
        if (pendingFailsafeSession) {
            pendingFailsafeSession = false
            createNewSession(failsafeMode = true)
            terminalView.post { terminalView.showKeyboard() }
        } else if (!systemReady && DebianRuntime.isRemoved(this)) {
            showRemovedRootfsDialog()
        } else if (!systemReady) {
            isBootstrapping = true
            bootstrapCancelled = false
            val progress = BootstrapDialog(this)
            bootstrapDialog = progress
            progress.setOnCancel { bootstrapCancelled = true }
            val progressRef = java.lang.ref.WeakReference(progress)
            try {
                progress.show()
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to show progress dialog", e)
            }
            kotlin.concurrent.thread(name = "Miauterm-Debian") {

                val success = try {
                    runBootstrapOnce { percent ->
                        if (!bootstrapCancelled) {
                            runOnUiThread {
                                val dialog = progressRef.get() ?: return@runOnUiThread
                                if (!isFinishing && !isDestroyed && !bootstrapCancelled && dialog.isShowing) {
                                    dialog.isIndeterminate = false
                                    dialog.progress = percent
                                }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    android.util.Log.e("MainActivity", "Bootstrap failed", t)
                    false
                }

                runOnUiThread {
                    try {
                        progressRef.get()?.takeIf { it.isShowing }?.dismiss()
                    } catch (e: Exception) {}
                    bootstrapDialog = null
                    isBootstrapping = false
                    if (isFinishing || isDestroyed || bootstrapCancelled) {
                        return@runOnUiThread
                    }
                    if (success) {
                        startInitialSession()
                    } else {
                        val detail = DebianRuntime.lastInstallError
                        val message = if (detail.isNullOrBlank()) getString(R.string.debian_install_failed)
                        else getString(R.string.debian_install_failed_details, detail)
                        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        } else {
            if (sessionManager.sessionCount() == 0) {
                startInitialSession()
            } else {
                val current = sessionManager.currentSession ?: sessionManager.sessionAt(0)
                terminalView.session = current
                sessionAdapter.notifyDataSetChanged()
                refreshTabs()
                terminalView.invalidate()
            }
            terminalView.post {
                terminalView.showKeyboard()
            }
        }
    }

    private fun confirmExit() {
        AlertDialog.Builder(this)
            .setTitle(R.string.exit_confirm_title)
            .setMessage(R.string.exit_confirm_message)
            .setPositiveButton(R.string.yes) { _, _ ->
                CortexService.instance?.exitAll() ?: run {
                    sessionManager.destroyAll()
                    finish()
                }
            }
            .setNegativeButton(R.string.no, null)
            .showInChrome()
    }

    private fun closeSession(index: Int) {
        val session = sessionManager.sessionAt(index) ?: return
        if (sessionManager.sessionCount() > 1) {
            sessionManager.removeSession(session)
            sessionAdapter.notifyDataSetChanged()
            refreshTabs()
            CortexService.updateNotification(this)
        } else {
            confirmExit()
        }
    }

    private fun refreshTabs() {
        if (!::sessionManager.isInitialized) return
        val sessions = sessionManager.snapshot()
        val tabs = sessions.map { s -> SessionTabsView.Tab(s.promptLabel()) }
        val active = sessionManager.currentSessionIndex
        binding.sessionTabs.update(tabs, active)
    }

    override fun onResume() {
        super.onResume()
        if (waitingForNotificationPrompt && notificationPromptInterruptedActivity) {
            waitingForNotificationPrompt = false
            notificationPromptInterruptedActivity = false

            initializeRuntime()
        }
        applyPreferences()

        extraKeysConfig.setHome(Environment.getHomeDir(this))
        applyExtraKeys()
        extraKeysConfig.watch {
            terminalView.removeCallbacks(applyExtraKeysRunnable)
            terminalView.postDelayed(applyExtraKeysRunnable, 150)
        }
        if (!::sessionManager.isInitialized || !::sessionAdapter.isInitialized) return
        val failsafeSessionActive = pendingFailsafeSession || sessionManager.currentSession?.failsafeMode == true
        if (!failsafeSessionActive && DebianRuntime.isRemoved(this) && !removedRootfsNoticeShown) {
            showRemovedRootfsDialog()
        }
        if (!failsafeSessionActive && DebianRuntime.isInstalled(this)) {
            runCatching { MiautermCommandInstaller.install(this, Environment.getCortexRoot(this)) }
                .onFailure { android.util.Log.e("MainActivity", "Could not update miauterm-cmd in Debian", it) }
        }
        var current = sessionManager.currentSession
        if (current != null) {
            terminalView.session = current
        } else if (!isBootstrapping && DebianRuntime.isInstalled(this) && sessionManager.sessionCount() == 0 && !pendingFailsafeSession) {
            restoreOrCreateSession()
        }
        if (!isBootstrapping && pendingFailsafeSession) {
            pendingFailsafeSession = false
            createNewSession(failsafeMode = true)
        }
        current = sessionManager.currentSession
        if (!isBootstrapping && DebianRuntime.isInstalled(this)) {
            pendingSessionCommand?.let { command ->
                val target = current ?: createNewSession()
                if (target.isRunning) {
                    pendingSessionCommand = null
                    target.write("$command\r")
                }
            }
        }
        terminalView.requestLayout()
        terminalView.postInvalidate()
        refreshTabs()
    }

    override fun onStop() {
        super.onStop()

        if (::sessionManager.isInitialized) sessionManager.saveState()
    }

    override fun onPause() {
        super.onPause()
        if (waitingForNotificationPrompt) notificationPromptInterruptedActivity = true
        terminalView.clearSelection()
        extraKeysConfig.stopWatching()
        terminalView.removeCallbacks(applyExtraKeysRunnable)
    }

    internal fun reloadExtraKeys() {
        if (::extraKeysConfig.isInitialized) {
            extraKeysConfig.setHome(Environment.getHomeDir(this))
            applyExtraKeys(force = true)
            extraKeysView.scrollLastRowToEnd()
        }
    }

    private fun applyExtraKeys(force: Boolean = false) {
        if (DebianRuntime.isInstalled(this)) {
            runCatching { extraKeysConfig.ensureConfigFile() }
                .onFailure { android.util.Log.e("MainActivity", "Could not create the default extra-keys file", it) }
        }
        val result = if (force) extraKeysConfig.reload() else extraKeysConfig.loadIfChanged() ?: return
        val rows = result.rows
            .map { row -> row.filterNot { it is ExtraKeysLayout.SpecialKey && it.special == ExtraKeysLayout.Special.FIND } }
            .filter { it.isNotEmpty() }
            .ifEmpty { ExtraKeysLayout.DEFAULT_ROWS }
        extraKeysView.setKeys(rows)
        val bar = binding.bottomBarContainer
        val height = (ExtraKeysView.barHeightDp(rows.size) * resources.displayMetrics.density).toInt()
        if (bar.layoutParams.height != height) {
            bar.layoutParams = bar.layoutParams.apply { this.height = height }
        }
        extraKeysView.scrollLastRowToEnd()
        result.error?.let {
            Toast.makeText(this, getString(R.string.extra_keys_error, it), Toast.LENGTH_LONG).show()
        }
    }

    internal fun reloadTerminalFont(): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val fontKey = prefs.getString("terminal_font", "jetbrains_mono") ?: "jetbrains_mono"
        return terminalView.setTerminalFont(fontKey, forceReload = true)
    }

    private var appliedPreferencesKey: String? = null

    private fun applyPreferences() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val signature = listOf(
            prefs.getString("terminal_font_size", "11"),
            prefs.getString("terminal_font", "jetbrains_mono"),
            prefs.getBoolean("show_session_tabs", true),
            prefs.getBoolean("keep_screen_on", false),
            prefs.getBoolean("secure_screen", false),
            org.cortex.terminal.emulator.TerminalColor.currentThemeId
        ).joinToString("|")
        if (signature == appliedPreferencesKey) return
        appliedPreferencesKey = signature
        val fontSizeStr = prefs.getString("terminal_font_size", "11") ?: "11"
        val fontSize = fontSizeStr.toFloatOrNull() ?: 11f
        terminalView.setTerminalTextSize(fontSize)

        if (prefs.contains("terminal_theme")) prefs.edit().remove("terminal_theme").apply()
        org.cortex.terminal.emulator.TerminalColor.applyTheme("miauterm")

        org.cortex.terminal.emulator.TerminalColor.takeRemap()?.let { remap ->
            if (::sessionManager.isInitialized) {
                sessionManager.snapshot().forEach { it.emulator.remapColors(remap) }
            }
        }
        Chrome.apply(org.cortex.terminal.emulator.TerminalColor.SCHEMES.getValue(org.cortex.terminal.emulator.TerminalColor.currentThemeId))
        terminalView.applyTheme()
        applyChrome()

        val showTabs = prefs.getBoolean("show_session_tabs", true)
        binding.sessionTabs.visibility = if (showTabs) View.VISIBLE else View.GONE
        binding.tabsDivider.visibility = if (showTabs) View.VISIBLE else View.GONE

        val fontKey = prefs.getString("terminal_font", "jetbrains_mono") ?: "jetbrains_mono"
        terminalView.setTerminalFont(fontKey)

        val keepScreenOn = prefs.getBoolean("keep_screen_on", false)
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val secureScreen = prefs.getBoolean("secure_screen", false)
        if (secureScreen) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun applyChrome() {
        val p = Chrome.palette

        window.setBackgroundDrawable(ColorDrawable(p.bg))
        drawerLayout.setBackgroundColor(p.bg)
        drawerLayout.setScrimColor(Chrome.withAlpha(Chrome.BLACK, 150))
        binding.sessionTabs.applyPalette()
        binding.tabsDivider.visibility = View.GONE
        binding.dockDivider.visibility = View.GONE
        binding.bottomBarContainer.setBackgroundColor(p.bar)
        extraKeysView.applyPalette()

        binding.navDrawer.setBackgroundColor(p.bar)
        binding.drawerTitle.setTextColor(p.text)
        binding.drawerDivider.visibility = View.GONE
        binding.drawerSessionsLabel.setTextColor(p.textFaint)
        val iconTint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
            intArrayOf(p.accent, p.textDim)
        )
        btnDrawerSettings.background = null
        btnDrawerFiles.background = null
        btnDrawerSettings.stateListAnimator = null
        btnDrawerFiles.stateListAnimator = null
        btnDrawerSettings.imageTintList = iconTint
        btnDrawerFiles.imageTintList = iconTint
        binding.drawerKeyboardIcon.imageTintList = ColorStateList.valueOf(p.textDim)
        binding.drawerNewSessionIcon.imageTintList = ColorStateList.valueOf(p.textDim)
        binding.drawerKeyboardLabel.setTextColor(p.text)
        binding.drawerNewSessionLabel.setTextColor(p.text)
        btnDrawerKeyboard.background = Chrome.flatPressable(p.bar, p.raisedHigh)
        btnDrawerNewSession.background = Chrome.flatPressable(p.bar, p.raisedHigh)
        btnDrawerKeyboard.elevation = 0f
        btnDrawerNewSession.elevation = 0f
        if (::sessionAdapter.isInitialized) sessionAdapter.notifyDataSetChanged()

        window.statusBarColor = p.bar
        window.navigationBarColor = p.bar
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !p.dark
        controller.isAppearanceLightNavigationBars = !p.dark
    }

    private fun showRemovedRootfsDialog() {
        if (removedRootfsNoticeShown || isFinishing || isDestroyed) return
        removedRootfsNoticeShown = true
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.rootfs_removed_title)
            .setMessage(R.string.rootfs_removed_message)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun confirmRootfsReinstall(): Boolean {
        if (isFinishing || isDestroyed) return false
        val confirmed = java.util.concurrent.atomic.AtomicBoolean(false)
        val latch = java.util.concurrent.CountDownLatch(1)
        runOnUiThread {
            if (isFinishing || isDestroyed) {
                latch.countDown()
                return@runOnUiThread
            }
            try {
                AlertDialog.Builder(this)
                    .setTitle(R.string.debian_reinstall_confirm_title)
                    .setMessage(R.string.debian_reinstall_confirm_message)
                    .setNegativeButton(android.R.string.cancel) { _, _ -> latch.countDown() }
                    .setPositiveButton(R.string.pref_reset_debian_title) { _, _ ->
                        confirmed.set(true)
                        latch.countDown()
                    }
                    .setOnCancelListener { latch.countDown() }
                    .show()
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Could not show rootfs confirmation", e)
                latch.countDown()
            }
        }
        if (!latch.await(3, java.util.concurrent.TimeUnit.MINUTES)) return false
        return confirmed.get()
    }

    private fun runBootstrapOnce(onProgress: ((Int) -> Unit)? = null): Boolean {
        val app = applicationContext
        val owner = synchronized(bootstrapLock) {
            if (bootstrapInProgress) false else {
                bootstrapInProgress = true
                true
            }
        }
        if (!owner) {
            while (synchronized(bootstrapLock) { bootstrapInProgress }) Thread.sleep(250)
            return DebianRuntime.isInstalled(app)
        }
        return try {
            DebianRuntime.install(app, onProgress)
        } finally {
            synchronized(bootstrapLock) { bootstrapInProgress = false }
        }
    }

    private fun restoreOrCreateSession() {
        if (sessionManager.restore(terminalView.rows, terminalView.cols, terminalView.width, terminalView.height)) {
            terminalView.session = sessionManager.currentSession
            sessionAdapter.notifyDataSetChanged()
            refreshTabs()
            CortexService.updateNotification(this)
        } else {
            createNewSession()
        }
    }

    private fun showNewSessionDialog() {
        val p = Chrome.palette
        val density = resources.displayMetrics.density
        val nameInput = android.widget.EditText(this).apply {
            hint = getString(R.string.new_session_name_hint)
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextColor(p.text)
            setHintTextColor(p.textFaint)
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            background = Chrome.flatPressable(p.raised, p.raisedHigh)
        }
        val failsafe = android.widget.CheckBox(this).apply {
            text = getString(R.string.new_session_failsafe)
            setTextColor(p.text)
            buttonTintList = ColorStateList.valueOf(p.accent)
        }
        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((22 * density).toInt(), (8 * density).toInt(), (22 * density).toInt(), 0)
            addView(nameInput, android.widget.LinearLayout.LayoutParams(-1, -2))
            addView(failsafe, android.widget.LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = (8 * density).toInt()
            })
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.drawer_new_session)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.create) { _, _ ->
                val name = nameInput.text?.toString()?.trim()?.take(48)?.ifBlank { null }
                createNewSession(failsafeMode = failsafe.isChecked, sessionName = name)
            }
            .create()
        ChromeDialog.style(dialog).show()
        nameInput.requestFocus()
    }

    private fun showRenameSessionDialog(index: Int) {
        val session = sessionManager.sessionAt(index) ?: return
        val p = Chrome.palette
        val density = resources.displayMetrics.density
        val input = android.widget.EditText(this).apply {
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(session.sessionName.orEmpty())
            hint = getString(R.string.new_session_name_hint)
            setTextColor(p.text)
            setHintTextColor(p.textFaint)
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            background = Chrome.flatPressable(p.raised, p.raisedHigh)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.session_rename_title)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text?.toString()?.trim()?.take(48)?.ifBlank { null }
                session.sessionName = name
                sessionAdapter.notifyDataSetChanged()
                refreshTabs()
                CortexService.updateNotification(this)
            }
            .create()
        ChromeDialog.style(dialog).show()
        input.requestFocus()
    }

    private fun createNewSession(failsafeMode: Boolean = false, sessionName: String? = null): TerminalSession {
        val session = sessionManager.newSession(
            rows = terminalView.rows,
            cols = terminalView.cols,
            widthPx = terminalView.width,
            heightPx = terminalView.height,
            failsafeMode = failsafeMode,
            sessionName = sessionName
        )
        terminalView.session = session
        sessionAdapter.notifyDataSetChanged()
        refreshTabs()
        return session
    }

    private fun setupBackPressedDispatcher() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                    return
                }

                if (sessionManager.sessionCount() > 1) {
                    val current = sessionManager.currentSession
                    if (current != null) {
                        sessionManager.removeSession(current)
                        sessionAdapter.notifyDataSetChanged()
                        refreshTabs()
                        return
                    }
                }

                confirmExit()
            }
        })
    }

    private fun startInitialSession() {
        applyExtraKeys()
        openInitialSession()
    }

    private fun openInitialSession() {
        restoreOrCreateSession()
        terminalView.post { terminalView.showKeyboard() }
    }

    override fun onDestroy() {
        try {
            if (bootstrapDialog?.isShowing == true) {
                bootstrapDialog?.dismiss()
            }
        } catch (e: Exception) {}
        bootstrapDialog = null
        if (::sessionManager.isInitialized) {
            sessionManager.onSessionChanged = null
        }
        terminalView.onUrlTapped = null
        terminalView.onTextSizeChangedByPinch = null
        terminalView.session = null
        if (instance == this) instance = null
        if (runtimeInitializationStarted && ::sessionManager.isInitialized && sessionManager.sessionCount() == 0) {
            CortexService.stop(this)
        }
        super.onDestroy()
    }
}
