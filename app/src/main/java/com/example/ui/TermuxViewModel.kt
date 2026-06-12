package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.engine.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

class TermuxViewModel(application: Application) : AndroidViewModel(application) {

    private val database = TermuxDatabase.getDatabase(application)
    private val repository = TermuxRepository(database.termuxDao())

    // All sessions in database
    val sessions = repository.allSessions.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Command History in database (for command recall using keyboard arrows)
    val commandHistory = repository.commandHistory.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Current active environment mode
    private val _currentMode = MutableStateFlow(EnvironmentMode.ANDROID)
    val currentMode: StateFlow<EnvironmentMode> = _currentMode.asStateFlow()

    // Current active terminal theme state
    private val _currentTheme = MutableStateFlow(TerminalTheme.DRACULA)
    val currentTheme: StateFlow<TerminalTheme> = _currentTheme.asStateFlow()

    // Currently selected session ID
    private val _activeSessionId = MutableStateFlow(1)
    val activeSessionId: StateFlow<Int> = _activeSessionId.asStateFlow()

    // Screen lines per session ID (in memory state since output logs do not belong in SQLite)
    private val _sessionLines = MutableStateFlow<Map<Int, List<TerminalLine>>>(emptyMap())
    val sessionLines: StateFlow<Map<Int, List<TerminalLine>>> = _sessionLines.asStateFlow()

    // Interactive file editor state
    private val _editorState = MutableStateFlow<EditorState?>(null)
    val editorState: StateFlow<EditorState?> = _editorState.asStateFlow()

    // Matrix ASCII animation state
    private val _showMatrixAnimation = MutableStateFlow(false)
    val showMatrixAnimation: StateFlow<Boolean> = _showMatrixAnimation.asStateFlow()

    // Shared scroll trigger channel to notify scrolling Compose list to bottom on additions
    private val _scrollTrigger = MutableStateFlow(0)
    val scrollTrigger: StateFlow<Int> = _scrollTrigger.asStateFlow()

    // Initialize the execution engine
    val engine = TermuxEngine(
        context = application,
        onLineAdded = { sessionId, line ->
            addTerminalLine(sessionId, line)
        },
        onClearLines = { sessionId ->
            clearLinesForSession(sessionId)
        },
        onThemeChanged = { theme ->
            _currentTheme.value = theme
        },
        onEditorOpened = { filePath, isVim ->
            openEditorForFile(filePath, isVim)
        },
        onMatrixStarted = {
            _showMatrixAnimation.value = true
        },
        onPackagesUpdated = {
            syncPackagesWithEngine()
        }
    )

    init {
        // Collect DB aliases and sync with engine
        viewModelScope.launch {
            repository.allAliases.collect { aliases ->
                engine.updateAliases(aliases)
            }
        }

        // Collect DB installed packages and sync with engine
        viewModelScope.launch {
            repository.installedPackages.collect { pkgs ->
                engine.updateInstalledPackages(pkgs)
            }
        }

        // Setup default session if none exists on database launch
        viewModelScope.launch {
            sessions.collect { list ->
                if (list.isEmpty()) {
                    val defaultSession = TerminalSession(name = "Bash 1", isActive = true)
                    val newId = repository.insertSession(defaultSession)
                    _activeSessionId.value = newId
                    printWelcomeMessage(newId)
                } else {
                    val active = list.firstOrNull { it.isActive }
                    if (active != null) {
                        _activeSessionId.value = active.id
                        // Print welcome on first load of memory buffer if empty
                        if (_sessionLines.value[active.id].isNullOrEmpty()) {
                            printWelcomeMessage(active.id)
                        }
                    } else {
                        // Activate first if none is marked active
                        val first = list[0]
                        repository.activateSession(first.id)
                        _activeSessionId.value = first.id
                        if (_sessionLines.value[first.id].isNullOrEmpty()) {
                            printWelcomeMessage(first.id)
                        }
                    }
                }
            }
        }
    }

    private fun printWelcomeMessage(sessionId: Int) {
        val welcome = engine.generateWelcomeBanner()
        val list = welcome.split("\n").map { TerminalLine(it, LineType.SYSTEM) }
        _sessionLines.value = _sessionLines.value.toMutableMap().apply {
            put(sessionId, list)
        }
    }

    private fun syncPackagesWithEngine() {
        viewModelScope.launch {
            val list = repository.getInstalledPackagesList()
            engine.updateInstalledPackages(list)
        }
    }

    private fun addTerminalLine(sessionId: Int, line: TerminalLine) {
        val currentMap = _sessionLines.value.toMutableMap()
        val currentLines = currentMap[sessionId]?.toMutableList() ?: mutableListOf()
        
        // Limit terminal scroll logs to 300 entries to prevent memory resource leaking
        if (currentLines.size > 300) {
            currentLines.removeAt(0)
        }
        currentLines.add(line)
        currentMap[sessionId] = currentLines
        _sessionLines.value = currentMap

        // If line is for the currently active tab, increment scroll trigger!
        if (sessionId == _activeSessionId.value) {
            _scrollTrigger.value += 1
        }
    }

    private fun clearLinesForSession(sessionId: Int) {
        val currentMap = _sessionLines.value.toMutableMap()
        currentMap[sessionId] = emptyList()
        _sessionLines.value = currentMap
    }

    fun executeTerminalCommand(rawCommand: String) {
        val sessionId = _activeSessionId.value
        viewModelScope.launch {
            // Save non-empty commands to database history
            val cmd = rawCommand.trim()
            if (cmd.isNotEmpty() && !engine.isPythonActive(sessionId)) {
                repository.addHistory(cmd)
            }
            engine.processCommand(sessionId, rawCommand, viewModelScope)
        }
    }

    fun createNewSession() {
        viewModelScope.launch {
            val sessionsList = sessions.value
            val nextNum = if (sessionsList.isEmpty()) 1 else (sessionsList.maxOf { it.id } + 1)
            val newSessionName = "Bash $nextNum"
            val newSession = TerminalSession(name = newSessionName, isActive = true)
            val newId = repository.insertSession(newSession)
            repository.activateSession(newId)
            _activeSessionId.value = newId
            printWelcomeMessage(newId)
        }
    }

    fun switchSession(sessionId: Int) {
        viewModelScope.launch {
            repository.activateSession(sessionId)
            _activeSessionId.value = sessionId
            _scrollTrigger.value += 1
        }
    }

    fun removeSession(session: TerminalSession) {
        viewModelScope.launch {
            val currentList = sessions.value
            if (currentList.size <= 1) {
                // Cannot remove last remaining session
                addTerminalLine(_activeSessionId.value, TerminalLine("Termux: Cannot close the final session tab.", LineType.ERROR))
                return@launch
            }
            
            repository.deleteSession(session)
            // If we closed the active tab, switch to the remaining one
            if (session.id == _activeSessionId.value) {
                val remaining = currentList.firstOrNull { it.id != session.id }
                if (remaining != null) {
                    switchSession(remaining.id)
                }
            }
            
            // Clean memory buffer
            _sessionLines.value = _sessionLines.value.toMutableMap().apply {
                remove(session.id)
            }
        }
    }

    // --- Interactive Text Editor Helpers ---

    data class EditorState(
        val filePath: String,
        val content: String,
        val isVim: Boolean
    )

    private fun openEditorForFile(filePath: String, isVim: Boolean) {
        val file = File(filePath)
        val content = if (file.exists() && file.isFile) {
            file.readText()
        } else {
            ""
        }
        _editorState.value = EditorState(filePath, content, isVim)
    }

    fun saveEditorContent(content: String) {
        val state = _editorState.value ?: return
        try {
            val file = File(state.filePath)
            file.parentFile?.mkdirs()
            file.writeText(content)
            addTerminalLine(_activeSessionId.value, TerminalLine("File saved successfully: ${file.name} (${content.length} chars)", LineType.SUCCESS))
        } catch (e: Exception) {
            addTerminalLine(_activeSessionId.value, TerminalLine("Failed to save file: ${e.localizedMessage}", LineType.ERROR))
        }
        _editorState.value = null
    }

    fun closeEditor() {
        _editorState.value = null
    }

    // --- Matrix Retro Rain Mode ---
    fun closeMatrixAnimation() {
        _showMatrixAnimation.value = false
    }

    // --- Quick Action Accessory Shortcuts ---
    fun addAliasFromTerminal(name: String, mappedValue: String) {
        viewModelScope.launch {
            repository.addAlias(name, mappedValue)
        }
    }
}
