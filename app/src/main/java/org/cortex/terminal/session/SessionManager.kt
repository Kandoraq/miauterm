package org.cortex.terminal.session

import android.content.Context

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.preference.PreferenceManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class SessionManager(private val context: Context) {

    companion object {
        const val MAX_SESSIONS = 8
    }

    fun interface SessionListener {
        fun onSessionChanged(session: TerminalSession?)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<SessionListener>()

    val sessions = mutableListOf<TerminalSession>()
    @Volatile
    var currentSessionIndex = -1
        private set

    @Volatile
    private var restoring = false

    var onSessionChanged: ((TerminalSession?) -> Unit)? = null

    var store: SessionStore? = null

    private val saveExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Cortex-SessionStore").apply { isDaemon = true }
    }

    private fun restoreEnabled() =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_RESTORE_SESSIONS, true)

    fun saveState() {
        if (restoring) return
        val target = store ?: return
        val snapshot: List<TerminalSession>
        val current: Int
        synchronized(sessions) {
            snapshot = ArrayList(sessions)
            current = currentSessionIndex
        }
        try {
            saveExecutor.execute {
                try {
                    if (!restoreEnabled()) {
                        target.clear()
                        return@execute
                    }
                    val saved = snapshot.map { SessionStore.Saved(it.currentDirectory(), it.transcript(), it.sessionName) }
                    target.save(SessionStore.State(saved, current.coerceAtLeast(0)))
                } catch (e: Exception) {
                    Log.w(TAG, "Could not keep the sessions: ${e.message}")
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    fun restore(rows: Int, cols: Int, widthPx: Int, heightPx: Int): Boolean {
        val target = store ?: return false
        if (!restoreEnabled()) return false
        val state = target.load() ?: return false
        val started = ArrayList<Pair<Int, TerminalSession>>()
        restoring = true
        try {
            state.sessions.take(MAX_SESSIONS).forEachIndexed { index, saved ->
                val session = newSession(
                    rows, cols, widthPx, heightPx,
                    startDirectory = saved.directory,
                    restoredOutput = saved.output,
                    sessionName = saved.name
                )
                if (session.isRunning) {
                    started += index to session
                } else {
                    removeSession(session)
                }
            }
        } finally {
            restoring = false
        }
        if (started.isEmpty()) return false
        val current = started.indexOfFirst { it.first == state.current }.takeIf { it >= 0 } ?: 0
        switchTo(current)
        saveState()
        return true
    }

    val currentSession: TerminalSession?
        get() = synchronized(sessions) {
            if (currentSessionIndex in 0 until sessions.size) {
                sessions[currentSessionIndex]
            } else {
                null
            }
        }

    fun snapshot(): List<TerminalSession> = synchronized(sessions) { ArrayList(sessions) }

    fun sessionCount(): Int = synchronized(sessions) { sessions.size }

    fun canCreateSession(): Boolean = synchronized(sessions) { sessions.size < MAX_SESSIONS }

    fun sessionAt(index: Int): TerminalSession? = synchronized(sessions) { sessions.getOrNull(index) }

    fun addListener(listener: SessionListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: SessionListener) {
        listeners.remove(listener)
    }

    private fun notifySessionChanged(session: TerminalSession?) {
        mainHandler.post {
            onSessionChanged?.invoke(session)
            for (listener in listeners) {
                listener.onSessionChanged(session)
            }
        }
    }

    fun newSession(
        rows: Int = 24,
        cols: Int = 80,
        widthPx: Int = 0,
        heightPx: Int = 0,
        onRedraw: (() -> Unit)? = null,
        startDirectory: String? = null,
        restoredOutput: String? = null,
        failsafeMode: Boolean = false,
        accountName: String? = null,
        sessionName: String? = null
    ): TerminalSession {
        val session = TerminalSession(context.applicationContext, rows, cols, widthPx, heightPx, onRedraw, failsafeMode, accountName, sessionName)
        session.startDirectory = startDirectory
        session.restoredOutput = restoredOutput
        session.numberProvider = { synchronized(sessions) { sessions.indexOf(session) + 1 }.coerceAtLeast(1) }
        session.onWorkingDirectoryChanged = { notifySessionChanged(session) }
        val startTime = System.currentTimeMillis()
        session.onSessionFinished = { exitCode ->
            val duration = System.currentTimeMillis() - startTime
            org.cortex.terminal.runtime.DebianRuntime.recordRuntimeEvent(
                context,
                "Session finished with exit code $exitCode after ${duration}ms"
            )
            if (exitCode == 0 && duration > SESSION_START_GRACE_MS) {
                removeSession(session)
            }
        }
        session.onCloseRequested = {
            removeSession(session)
        }
        synchronized(sessions) {
            check(sessions.size < MAX_SESSIONS) { "Miauterm supports up to $MAX_SESSIONS sessions." }
            sessions.add(session)
            currentSessionIndex = sessions.size - 1
        }
        session.start()
        notifySessionChanged(session)
        saveState()
        return session
    }

    fun switchTo(index: Int) {
        val sessionToNotify: TerminalSession?
        synchronized(sessions) {
            if (index in 0 until sessions.size && index != currentSessionIndex) {
                currentSessionIndex = index
                sessionToNotify = currentSession
            } else {
                return
            }
        }
        notifySessionChanged(sessionToNotify)
        saveState()
    }

    fun removeSession(session: TerminalSession) {
        val sessionToNotify: TerminalSession?
        var toDestroy: TerminalSession? = null
        synchronized(sessions) {
            val idx = sessions.indexOf(session)
            if (idx >= 0) {
                toDestroy = session
                sessions.removeAt(idx)
                if (sessions.isEmpty()) {
                    currentSessionIndex = -1
                    sessionToNotify = null
                } else {
                    currentSessionIndex = currentSessionIndex.coerceIn(0, sessions.size - 1)
                    sessionToNotify = currentSession
                }
            } else {
                return
            }
        }
        toDestroy?.destroy()
        notifySessionChanged(sessionToNotify)
        saveState()
    }

    fun destroyAll(
        forget: Boolean = true,
        waitForProcesses: Boolean = false,
        notify: Boolean = true
    ) {
        val sessionsToDestroy: List<TerminalSession>
        synchronized(sessions) {
            sessionsToDestroy = ArrayList(sessions)
            sessions.clear()
            currentSessionIndex = -1
        }
        for (s in sessionsToDestroy) {
            s.destroy()
        }
        if (waitForProcesses) {
            sessionsToDestroy.forEach { it.awaitProcessExit(3_000) }
        }
        if (forget) {
            val target = store
            if (waitForProcesses) {
                try {
                    saveExecutor.submit { target?.clear() }.get(5, java.util.concurrent.TimeUnit.SECONDS)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not clear saved sessions before replacing Debian", e)
                }
            } else {
                try {
                    saveExecutor.execute { target?.clear() }
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                }
            }
        }
        if (notify) notifySessionChanged(null)
    }

    companion object {
        private const val TAG = "SessionManager"
        const val PREF_RESTORE_SESSIONS = "restore_sessions"
        const val SESSION_START_GRACE_MS = 1500L
    }
}
