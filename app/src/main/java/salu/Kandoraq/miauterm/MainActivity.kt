package salu.Kandoraq.miauterm

import salu.Kandoraq.Miauterm.R
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import java.lang.ref.WeakReference
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.WindowCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import salu.Kandoraq.Miauterm.databinding.ActivityMainBinding
import salu.Kandoraq.miauterm.runtime.DebianRuntime
import salu.Kandoraq.miauterm.runtime.Environment
import salu.Kandoraq.miauterm.runtime.MiautermCommandInstaller
import salu.Kandoraq.miauterm.service.MiautermService
import salu.Kandoraq.miauterm.session.SessionAdapter
import salu.Kandoraq.miauterm.session.SessionManager
import salu.Kandoraq.miauterm.session.TerminalSession
import salu.Kandoraq.miauterm.view.Chrome
import salu.Kandoraq.miauterm.view.ChromeDialog
import salu.Kandoraq.miauterm.view.ExtraKeysConfig
import salu.Kandoraq.miauterm.view.ExtraKeysLayout
import salu.Kandoraq.miauterm.view.ExtraKeysView
import salu.Kandoraq.miauterm.view.SessionTabsView
import salu.Kandoraq.miauterm.view.TerminalView
import salu.Kandoraq.miauterm.view.showInChrome

class MainActivity : AppCompatActivity() {

    companion object {
        @Volatile
        var instance: WeakReference<MainActivity>? = null
            private set

        @Volatile
        var pendingSessionCommand: String? = null
        @Volatile
        var pendingFailsafeSession = false

        private val bootstrapLock = Any()
        private var bootstrapInProgress = false
        private const val EXTRA_SHORTCUT_ACTION = "salu.Kandoraq.Miauterm.extra.SHORTCUT_ACTION"
        private const val SHORTCUT_NEW_SESSION = "new_session"
        private const val SHORTCUT_FAILSAFE = "failsafe"
        private const val SHORTCUT_SETTINGS = "settings"

        private fun runBootstrapOnce(
            app: android.content.Context,
            onProgress: ((Int) -> Unit)? = null,
            isCancelled: () -> Boolean
        ): Boolean {
            val owner = synchronized(bootstrapLock) {
                if (bootstrapInProgress) false else {
                    bootstrapInProgress = true
                    true
                }
            }
            if (!owner) {
                while (synchronized(bootstrapLock) { bootstrapInProgress }) {
                    if (isCancelled()) return false
                    Thread.sleep(250)
                }
                return !isCancelled() && DebianRuntime.isInstalled(app)
            }
            return try {
                DebianRuntime.install(app, onProgress, isCancelled = isCancelled)
            } finally {
                synchronized(bootstrapLock) { bootstrapInProgress = false }
            }
        }
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
    private val extraKeysExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Miauterm-ExtraKeys").apply { isDaemon = true }
    }
    private val extraKeysGeneration = java.util.concurrent.atomic.AtomicInteger()
    private var isBootstrapping = false
    private var runtimeInitializationStarted = false
    private var bootstrapDialog: BootstrapDialog? = null
    private val reinstallConfirmationHandler: () -> Boolean = { confirmRootfsReinstall() }
    @Volatile
    private var bootstrapCancelFlag: java.util.concurrent.atomic.AtomicBoolean? = null
    private var shortcutNewSession = false
    private var shortcutSettings = false
    private var releaseCheckStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        if (preferences.contains("app_language")) preferences.edit().remove("app_language").apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            AppCompatDelegate.getApplicationLocales().toLanguageTags() != "en"
        ) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
        }
        when (intent?.getStringExtra(EXTRA_SHORTCUT_ACTION)) {
            SHORTCUT_SETTINGS -> shortcutSettings = true
            SHORTCUT_NEW_SESSION -> shortcutNewSession = true
            SHORTCUT_FAILSAFE -> pendingFailsafeSession = true
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        DebianRuntime.reinstallConfirmation = reinstallConfirmationHandler
        extraKeysConfig = ExtraKeysConfig(Environment.getHomeDir(this))

        setupBackPressedDispatcher()
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
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

        instance = WeakReference(this)
        initializeRuntime()
    }

    private fun initializeRuntime() {
        if (runtimeInitializationStarted || isFinishing || isDestroyed) return
        runtimeInitializationStarted = true

        sessionManager = MiautermService.getOrCreateSessionManager(this)
        try {
            MiautermService.start(this)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "MiautermService.start failed", e)
        }

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
                if (isFinishing || isDestroyed || !runtimeInitializationStarted) return@runOnUiThread
                if (session == null) {
                    if (!isBootstrapping && !pendingFailsafeSession) {
                        MiautermService.stop(this)
                        finish()
                    }
                } else {
                    MiautermService.updateNotification(this)
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
            showNewSessionDialog()
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        if (shortcutSettings) {
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
            return
        }
        applyPreferences()
        val systemReady = !pendingFailsafeSession && DebianRuntime.isInstalled(this)
        if (pendingFailsafeSession) {
            pendingFailsafeSession = false
            createNewSession(failsafeMode = true)
            terminalView.post { terminalView.showKeyboard() }
        } else if (!systemReady) {
            isBootstrapping = true
            val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
            bootstrapCancelFlag = cancelled
            val appContext = applicationContext
            val activityRef = java.lang.ref.WeakReference(this)
            val progress = BootstrapDialog(this)
            bootstrapDialog = progress
            progress.setOnCancel { cancelled.set(true) }
            val progressRef = java.lang.ref.WeakReference(progress)
            try {
                progress.show()
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to show progress dialog", e)
            }
            kotlin.concurrent.thread(name = "Miauterm-Debian") {
                val success = try {
                    runBootstrapOnce(appContext, { percent ->
                        if (!cancelled.get()) {
                            activityRef.get()?.runOnUiThread {
                                val activity = activityRef.get() ?: return@runOnUiThread
                                val dialog = progressRef.get() ?: return@runOnUiThread
                                if (!activity.isFinishing && !activity.isDestroyed && !cancelled.get() && dialog.isShowing) {
                                    dialog.isIndeterminate = false
                                    dialog.progress = percent
                                }
                            }
                        }
                    }, cancelled::get)
                } catch (t: Throwable) {
                    android.util.Log.e("MainActivity", "Bootstrap failed", t)
                    false
                }
                activityRef.get()?.runOnUiThread {
                    val activity = activityRef.get() ?: return@runOnUiThread
                    try {
                        progressRef.get()?.takeIf { it.isShowing }?.dismiss()
                    } catch (_: Exception) {}
                    activity.bootstrapDialog = null
                    activity.bootstrapCancelFlag = null
                    activity.isBootstrapping = false
                    if (activity.isFinishing || activity.isDestroyed || cancelled.get()) return@runOnUiThread
                    if (success) {
                        activity.startInitialSession()
                    } else {
                        val detail = DebianRuntime.lastInstallError
                        val message = if (detail.isNullOrBlank()) activity.getString(R.string.debian_install_failed)
                        else activity.getString(R.string.debian_install_failed_details, detail)
                        android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        } else {
            if (shortcutNewSession) {
                shortcutNewSession = false
                createNewSession()
            } else if (sessionManager.sessionCount() == 0) {
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
                MiautermService.instance?.exitAll() ?: run {
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
            MiautermService.updateNotification(this)
        } else {
            confirmExit()
        }
    }

    private fun refreshTabs() {
        if (!::sessionManager.isInitialized) return
        val sessions = sessionManager.snapshot()
        val tabs = sessions.mapIndexed { index, session ->
            SessionTabsView.Tab(
                session.sessionName?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.session_label, index + 1)
            )
        }
        val active = sessionManager.currentSessionIndex
        binding.sessionTabs.update(tabs, active, canAdd = sessions.size < SessionManager.MAX_SESSIONS)
        btnDrawerNewSession.isEnabled = sessions.size < SessionManager.MAX_SESSIONS
    }

    override fun onResume() {
        super.onResume()
        applyPreferences()

        applyExtraKeys(watch = true)
        if (!::sessionManager.isInitialized || !::sessionAdapter.isInitialized) return
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
        checkForReleaseNotification()
    }

    private fun checkForReleaseNotification() {
        if (releaseCheckStarted) return
        if (!androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        releaseCheckStarted = true
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val now = System.currentTimeMillis()
        val lastCheck = preferences.getLong("last_github_release_check", 0L)
        if (now - lastCheck < 24L * 60L * 60L * 1000L) return
        preferences.edit().putLong("last_github_release_check", now).apply()
        Thread({
            when (val result = GitHubUpdateChecker.check()) {
                is GitHubUpdateChecker.Result.Available -> GitHubUpdateChecker.postNotification(applicationContext, result.release)
                GitHubUpdateChecker.Result.Current, is GitHubUpdateChecker.Result.Failed -> Unit
            }
        }, "Miauterm-ReleaseCheck").apply { isDaemon = true }.start()
    }

    override fun onStop() {
        super.onStop()

        if (::sessionManager.isInitialized) sessionManager.saveState()
    }

    override fun onPause() {
        super.onPause()
        terminalView.clearSelection()
        if (::extraKeysConfig.isInitialized) {
            runCatching { extraKeysExecutor.execute { extraKeysConfig.stopWatching() } }
        }
        terminalView.removeCallbacks(applyExtraKeysRunnable)
    }

    internal fun reloadExtraKeys() {
        if (::extraKeysConfig.isInitialized) applyExtraKeys(force = true)
    }

    private fun applyExtraKeys(force: Boolean = false, watch: Boolean = false) {
        if (!::extraKeysConfig.isInitialized || isDestroyed) return
        val generation = extraKeysGeneration.incrementAndGet()
        val home = Environment.getHomeDir(applicationContext)
        val app = applicationContext
        val activityRef = java.lang.ref.WeakReference(this)
        try {
            extraKeysExecutor.execute {
                val result = runCatching {
                    extraKeysConfig.setHome(home)
                    if (DebianRuntime.isInstalled(app)) {
                        runCatching { MiautermCommandInstaller.install(app, Environment.getMiautermRoot(app)) }
                            .onFailure { android.util.Log.e("MainActivity", "Could not update miauterm-cmd in Debian", it) }
                        runCatching { extraKeysConfig.ensureConfigFile() }
                            .onFailure { android.util.Log.e("MainActivity", "Could not create the default extra-keys file", it) }
                    }
                    if (watch) {
                        extraKeysConfig.watch {
                            activityRef.get()?.runOnUiThread {
                                activityRef.get()?.let { activity ->
                                    activity.terminalView.removeCallbacks(activity.applyExtraKeysRunnable)
                                    activity.terminalView.postDelayed(activity.applyExtraKeysRunnable, 150)
                                }
                            }
                        }
                    }
                    if (force) extraKeysConfig.reload() else extraKeysConfig.loadIfChanged()
                }.onFailure { android.util.Log.e("MainActivity", "Could not apply extra-key configuration", it) }.getOrNull()
                    ?: return@execute
                val rows = result.rows
                    .map { row -> row.filterNot { it is ExtraKeysLayout.SpecialKey && it.special == ExtraKeysLayout.Special.FIND } }
                    .filter { it.isNotEmpty() }
                    .ifEmpty { ExtraKeysLayout.DEFAULT_ROWS }
                activityRef.get()?.runOnUiThread {
                    val activity = activityRef.get() ?: return@runOnUiThread
                    if (activity.isFinishing || activity.isDestroyed || generation != activity.extraKeysGeneration.get()) return@runOnUiThread
                    activity.extraKeysView.setKeys(rows)
                    val bar = activity.binding.bottomBarContainer
                    val height = (ExtraKeysView.barHeightDp(rows.size) * activity.resources.displayMetrics.density).toInt()
                    if (bar.layoutParams.height != height) {
                        bar.layoutParams = bar.layoutParams.apply { this.height = height }
                    }
                    activity.extraKeysView.scrollLastRowToEnd()
                    result.error?.let { error ->
                        Toast.makeText(activity, activity.getString(R.string.extra_keys_error, error), Toast.LENGTH_LONG).show()
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            Unit
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
            salu.Kandoraq.miauterm.emulator.TerminalColor.currentThemeId
        ).joinToString("|")
        if (signature == appliedPreferencesKey) return
        appliedPreferencesKey = signature
        val fontSizeStr = prefs.getString("terminal_font_size", "11") ?: "11"
        val fontSize = fontSizeStr.toFloatOrNull() ?: 11f
        terminalView.setTerminalTextSize(fontSize)

        if (prefs.contains("terminal_theme")) prefs.edit().remove("terminal_theme").apply()
        salu.Kandoraq.miauterm.emulator.TerminalColor.applyTheme("miauterm")

        salu.Kandoraq.miauterm.emulator.TerminalColor.takeRemap()?.let { remap ->
            if (::sessionManager.isInitialized) {
                sessionManager.snapshot().forEach { it.emulator.remapColors(remap) }
            }
        }
        Chrome.apply()
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
                    .setOnDismissListener { latch.countDown() }
                    .show()
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Could not show rootfs confirmation", e)
                latch.countDown()
            }
        }
        if (!latch.await(30, java.util.concurrent.TimeUnit.SECONDS)) return false
        return confirmed.get()
    }

    private fun restoreOrCreateSession() {
        if (sessionManager.restore(terminalView.rows, terminalView.cols, terminalView.width, terminalView.height)) {
            terminalView.session = sessionManager.currentSession
            sessionAdapter.notifyDataSetChanged()
            refreshTabs()
            MiautermService.updateNotification(this)
        } else {
            createNewSession()
        }
    }

    private fun showNewSessionDialog() {
        if (!sessionManager.canCreateSession()) {
            Toast.makeText(this, R.string.max_sessions_reached, Toast.LENGTH_SHORT).show()
            return
        }
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
                val session = createNewSession(failsafeMode = failsafe.isChecked, sessionName = name)
                session.sessionName = name
                sessionAdapter.notifyDataSetChanged()
                refreshTabs()
                android.widget.Toast.makeText(
                    this,
                    getString(R.string.session_created, name ?: getString(R.string.drawer_new_session)),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            .create()
        dialog.show()
        ChromeDialog.style(dialog)
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
                MiautermService.updateNotification(this)
            }
            .create()
        ChromeDialog.style(dialog).show()
        input.requestFocus()
    }

    private fun createNewSession(failsafeMode: Boolean = false, sessionName: String? = null): TerminalSession {
        if (!sessionManager.canCreateSession()) {
            Toast.makeText(this, R.string.max_sessions_reached, Toast.LENGTH_SHORT).show()
            return sessionManager.currentSession
                ?: throw IllegalStateException("No current session is available at the session limit.")
        }
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
                if (!::sessionManager.isInitialized) {
                    finish()
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
        if (shortcutNewSession) {
            shortcutNewSession = false
            createNewSession()
            terminalView.post { terminalView.showKeyboard() }
        } else {
            openInitialSession()
        }
    }

    private fun openInitialSession() {
        restoreOrCreateSession()
        terminalView.post { terminalView.showKeyboard() }
    }

    override fun onDestroy() {
        bootstrapCancelFlag?.set(true)
        if (DebianRuntime.reinstallConfirmation === reinstallConfirmationHandler) {
            DebianRuntime.reinstallConfirmation = null
        }
        if (::extraKeysConfig.isInitialized) extraKeysConfig.stopWatching()
        extraKeysExecutor.shutdownNow()
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
        if (instance?.get() == this) instance = null
        if (runtimeInitializationStarted && ::sessionManager.isInitialized && sessionManager.sessionCount() == 0) {
            MiautermService.stop(this)
        }
        super.onDestroy()
    }
}
