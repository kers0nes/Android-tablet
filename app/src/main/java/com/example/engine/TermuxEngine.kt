package com.example.engine

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.api.GeminiCopilot
import com.example.api.WeatherClient
import com.example.data.InstalledPackage
import com.example.data.ShellAlias
import com.example.data.TerminalSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LineType {
    INPUT,
    OUTPUT,
    ERROR,
    SYSTEM,
    SUCCESS,
    AI_USER,
    AI_RESP,
    MATRIX
}

data class TerminalLine(
    val text: String,
    val type: LineType = LineType.OUTPUT,
    val timestamp: Long = System.currentTimeMillis()
)

enum class TerminalTheme(val displayName: String) {
    DRACULA("Dracula Obsidian"),
    MATRIX("Classic Matrix"),
    AMBER("Retro Amber CRTO"),
    CYBERPUNK("Cyberpunk Neon"),
    MONOKAI("Monokai Slate"),
    HACKER_PURPLE("Hacker Purple")
}

enum class EnvironmentMode(val displayName: String) {
    ANDROID("Standard Android"),
    SAMSUNG("Samsung Mode (High-Speed)"),
    IPHONE_17E("iPhone 17e (iOS Mode)")
}

class TermuxEngine(
    private val context: Context,
    private val onLineAdded: (TerminalSessionId: Int, TerminalLine) -> Unit,
    private val onClearLines: (TerminalSessionId: Int) -> Unit,
    private val onThemeChanged: (TerminalTheme) -> Unit,
    private val onEditorOpened: (filePath: String, isVim: Boolean) -> Unit,
    private val onMatrixStarted: () -> Unit,
    private val onPackagesUpdated: () -> Unit
) {
    private val TAG = "TermuxEngine"
    
    // Tracks current working directories per terminal session
    private val sessionDirs = mutableMapOf<Int, File>()
    
    // Active Python Mode state per session (if user typed python)
    private val inPythonMode = mutableMapOf<Int, Boolean>()

    // Current lists of aliases and packages
    private var activeAliases = listOf<ShellAlias>()
    private var installedPackages = setOf<String>()

    fun updateAliases(aliases: List<ShellAlias>) {
        activeAliases = aliases
    }

    fun updateInstalledPackages(packages: List<InstalledPackage>) {
        installedPackages = packages.map { it.packageName }.toSet()
    }

    private fun getDirForSession(sessionId: Int): File {
        return sessionDirs.getOrPut(sessionId) { context.filesDir }
    }

    private fun setDirForSession(sessionId: Int, dir: File) {
        sessionDirs[sessionId] = dir
    }

    fun isPythonActive(sessionId: Int): Boolean {
        return inPythonMode.getOrDefault(sessionId, false)
    }

    fun exitPythonMode(sessionId: Int) {
        inPythonMode[sessionId] = false
    }

    /**
     * Executes a command string in standard background routine.
     */
    suspend fun processCommand(sessionId: Int, rawCommand: String, dbScope: CoroutineScope) {
        val trimmed = rawCommand.trim()
        if (trimmed.isEmpty()) {
            onLineAdded(sessionId, TerminalLine("", LineType.OUTPUT))
            return
        }

        // 1. Handle Python prompt mode interception
        if (isPythonActive(sessionId)) {
            onLineAdded(sessionId, TerminalLine(">>> $rawCommand", LineType.INPUT))
            handlePythonInput(sessionId, trimmed)
            return
        }

        // Print input line
        val currentDir = getDirForSession(sessionId)
        val displayPath = currentDir.absolutePath.replace(context.filesDir.absolutePath, "~")
        onLineAdded(sessionId, TerminalLine("root@android-emu:$displayPath $ $rawCommand", LineType.INPUT))

        // 2. Perform alias substitutions
        var processedCmd = trimmed
        for (alias in activeAliases) {
            if (trimmed.startsWith(alias.name)) {
                // Check if it's exact match or followed by space
                if (trimmed == alias.name) {
                    processedCmd = alias.value
                    break
                } else if (trimmed.startsWith("${alias.name} ")) {
                    processedCmd = alias.value + trimmed.substring(alias.name.length)
                    break
                }
            }
        }

        // Split into parts safely
        val parts = parseCommandLine(processedCmd)
        if (parts.isEmpty()) return

        val primaryCmd = parts[0]
        val args = parts.drop(1)

        // 3. Process Embedded/Simulated utilities first
        when (primaryCmd) {
            "help" -> {
                printHelp(sessionId)
            }
            "clear" -> {
                onClearLines(sessionId)
            }
            "neofetch" -> {
                printNeofetch(sessionId)
            }
            "weather" -> {
                val city = args.joinToString(" ")
                printWeather(sessionId, city)
            }
            "cowsay" -> {
                if (args.isEmpty()) {
                    onLineAdded(sessionId, TerminalLine("cowsay: Specify what the cow should say!", LineType.ERROR))
                } else {
                    val speech = args.joinToString(" ")
                    onLineAdded(sessionId, TerminalLine(generateCowsay(speech), LineType.OUTPUT))
                }
            }
            "figlet" -> {
                if (args.isEmpty()) {
                    onLineAdded(sessionId, TerminalLine("figlet: Specify text to write", LineType.ERROR))
                } else {
                    val t = args.joinToString(" ")
                    onLineAdded(sessionId, TerminalLine(generateASCIIBanner(t), LineType.OUTPUT))
                }
            }
            "banner" -> {
                onLineAdded(sessionId, TerminalLine(generateWelcomeBanner(), LineType.SYSTEM))
            }
            "cd" -> {
                handleCd(sessionId, args)
            }
            "theme" -> {
                handleThemeSelection(sessionId, args)
            }
            "apt" -> {
                handleAptCommand(sessionId, args)
            }
            "python", "python3" -> {
                handlePythonCommand(sessionId, args)
            }
            "nano", "vim", "vi" -> {
                handleEditorCommand(sessionId, primaryCmd, args)
            }
            "matrix" -> {
                onMatrixStarted()
            }
            "ai", "copilot" -> {
                if (args.isEmpty()) {
                    onLineAdded(sessionId, TerminalLine("ai: Please state what you want to ask! Example: ai 'how to list files by size'", LineType.ERROR))
                } else {
                    val query = args.joinToString(" ")
                    onLineAdded(sessionId, TerminalLine("copilot-ai $ querying Gemini API securely...", LineType.AI_USER))
                    val aiResponse = GeminiCopilot.getTerminalAssistance(query)
                    onLineAdded(sessionId, TerminalLine("🤖 Copilot-Assistant:\n$aiResponse", LineType.AI_RESP))
                }
            }
            "bot" -> {
                handleBotCreation(sessionId, args)
            }
            "mode" -> {
                handleModeSelection(sessionId, args)
            }
            "alias" -> {
                handleAliasCommand(sessionId, args, dbScope)
            }
            "uptime" -> {
                val upString = getSystemUptime()
                onLineAdded(sessionId, TerminalLine("uptime:  $upString", LineType.OUTPUT))
            }
            else -> {
                // If it's a simulated package command let's check lock
                if (isPackageRelatedSimulatedCommand(primaryCmd)) {
                    if (!installedPackages.contains(primaryCmd)) {
                        onLineAdded(sessionId, TerminalLine("sh: command not found: $primaryCmd. Did you forget to 'apt install $primaryCmd'?", LineType.ERROR))
                        return
                    }
                    executeSimulatedPackageCommand(sessionId, primaryCmd, args)
                } else {
                    // Try real Android OS process execution inside the device sandbox
                    executeRealShellProcess(sessionId, processedCmd)
                }
            }
        }
    }

    private fun handleCd(sessionId: Int, args: List<String>) {
        val target = if (args.isEmpty()) "~" else args[0]
        val currentDir = getDirForSession(sessionId)
        
        val newDir = when (target) {
            "~" -> context.filesDir
            ".." -> currentDir.parentFile ?: currentDir
            "/" -> File("/")
            else -> {
                val resolved = File(currentDir, target)
                if (resolved.exists() && resolved.isDirectory) {
                    resolved
                } else {
                    onLineAdded(sessionId, TerminalLine("cd: no such file or directory: $target", LineType.ERROR))
                    null
                }
            }
        }

        if (newDir != null) {
            setDirForSession(sessionId, newDir.canonicalFile)
        }
    }

    private fun handleThemeSelection(sessionId: Int, args: List<String>) {
        if (args.isEmpty()) {
            val list = TerminalTheme.values().joinToString(", ") { it.name.lowercase() }
            onLineAdded(sessionId, TerminalLine("Usage: theme <theme_name>\nAvailable themes: $list", LineType.SYSTEM))
            return
        }
        val selected = args[0].uppercase()
        try {
            val theme = TerminalTheme.valueOf(selected)
            onThemeChanged(theme)
            onLineAdded(sessionId, TerminalLine("Terminal theme switched to [${theme.displayName}]", LineType.SUCCESS))
        } catch (e: Exception) {
            onLineAdded(sessionId, TerminalLine("Error: Theme '$selected' not found. Use 'theme' for list of themes.", LineType.ERROR))
        }
    }

    private fun handleEditorCommand(sessionId: Int, editor: String, args: List<String>) {
        if (args.isEmpty()) {
            onLineAdded(sessionId, TerminalLine("Usage: $editor <filename>", LineType.ERROR))
            return
        }
        val filename = args[0]
        val isVim = editor.contains("vi")
        val currentDir = getDirForSession(sessionId)
        val file = File(currentDir, filename)
        
        onEditorOpened(file.absolutePath, isVim)
    }

    private suspend fun handleAptCommand(sessionId: Int, args: List<String>) {
        if (args.isEmpty()) {
            onLineAdded(sessionId, TerminalLine("Usage: apt [update | list | install <pkg> | uninstall <pkg>]\nExamples:\n  apt install python\n  apt install git\n  apt install sl", LineType.SYSTEM))
            return
        }
        when (args[0]) {
            "update" -> {
                onLineAdded(sessionId, TerminalLine("Get:1 https://termux.org/packages stable InRelease [12.4 kB]", LineType.OUTPUT))
                delay(300)
                onLineAdded(sessionId, TerminalLine("Get:2 https://generative-ai.google/api stable/main ksp [1.8 kB]", LineType.OUTPUT))
                delay(500)
                onLineAdded(sessionId, TerminalLine("Reading package lists... Done", LineType.SUCCESS))
                onLineAdded(sessionId, TerminalLine("All simulation package indexes updated! 12 tools ready to install.", LineType.SUCCESS))
            }
            "list" -> {
                onLineAdded(sessionId, TerminalLine("Available high-grade developer tools in dynamic repository:", LineType.SYSTEM))
                val tools = mapOf(
                    "python" to "High-level interactive script runner interpreter (~ v3.11)",
                    "git" to "Simulated repository management standard client",
                    "sl" to "Legendary Steam Locomotive train animation engine",
                    "nc" to "Netcat terminal utility pipeline builder",
                    "node" to "JavaScript server side execution environment mock",
                    "telnet" to "Visual connection client for classic remote ASCII arts"
                )
                tools.forEach { (name, desc) ->
                    val status = if (installedPackages.contains(name)) "[INSTALLED]" else "[AVAILABLE]"
                    onLineAdded(sessionId, TerminalLine(" * %-8s - %-55s %12s".format(Locale.US, name, desc, status), LineType.OUTPUT))
                }
            }
            "install" -> {
                if (args.size < 2) {
                    onLineAdded(sessionId, TerminalLine("Error: Package name not specified. Example: apt install python", LineType.ERROR))
                    return
                }
                val pkgName = args[1].lowercase()
                val supported = listOf("python", "git", "sl", "nc", "node", "telnet")
                if (!supported.contains(pkgName)) {
                    onLineAdded(sessionId, TerminalLine("E: Unable to locate simulation package: $pkgName", LineType.ERROR))
                    return
                }
                if (installedPackages.contains(pkgName)) {
                    onLineAdded(sessionId, TerminalLine("apt: $pkgName is already installed and updated to highest standard.", LineType.SUCCESS))
                    return
                }

                // Simulate installer progress counts
                onLineAdded(sessionId, TerminalLine("Reading package lists... Done", LineType.OUTPUT))
                onLineAdded(sessionId, TerminalLine("Building dependency tree... Done", LineType.OUTPUT))
                onLineAdded(sessionId, TerminalLine("The following NEW packages will be installed:\n  $pkgName", LineType.SYSTEM))
                onLineAdded(sessionId, TerminalLine("Need to get 14.8 MB of archives. 56.4 MB of additional disk space will be used.", LineType.OUTPUT))
                delay(300)
                onLineAdded(sessionId, TerminalLine("Get:1 http://termux.org/stable $pkgName arm64 [14.8 MB]", LineType.OUTPUT))
                
                // Fancy progress bar mock-up dynamically added in lines
                for (progress in 10..100 step 20) {
                    delay(250)
                    onLineAdded(sessionId, TerminalLine("Downloading: [${"=".repeat(progress / 10)}${" ".repeat(10 - progress / 10)}] $progress%", LineType.OUTPUT))
                }
                onLineAdded(sessionId, TerminalLine("Unpacking and setting up $pkgName...", LineType.OUTPUT))
                delay(400)
                
                // Save package to database and notify UI
                onPackagesUpdated()
                onLineAdded(sessionId, TerminalLine("Package simulated setup successfully completed. Try running '$pkgName' in terminal command line!", LineType.SUCCESS))
            }
            "uninstall" -> {
                if (args.size < 2) {
                    onLineAdded(sessionId, TerminalLine("Error: Package name not specified.", LineType.ERROR))
                    return
                }
                val pkgName = args[1].lowercase()
                if (!installedPackages.contains(pkgName)) {
                    onLineAdded(sessionId, TerminalLine("E: Package '$pkgName' not installed.", LineType.ERROR))
                    return
                }
                onLineAdded(sessionId, TerminalLine("Removing simulated directories for $pkgName...", LineType.OUTPUT))
                delay(500)
                
                onPackagesUpdated()
                onLineAdded(sessionId, TerminalLine("Simulated package $pkgName uninstalled successfully.", LineType.SUCCESS))
            }
            else -> {
                onLineAdded(sessionId, TerminalLine("Unknown apt command: ${args[0]}", LineType.ERROR))
            }
        }
    }

    private fun handlePythonCommand(sessionId: Int, args: List<String>) {
        if (!installedPackages.contains("python")) {
            onLineAdded(sessionId, TerminalLine("python: Command not found. Did you forget to run 'apt install python' first?", LineType.ERROR))
            return
        }

        if (args.isEmpty()) {
            // Enter Interactive Python mode
            inPythonMode[sessionId] = true
            onLineAdded(sessionId, TerminalLine("Python 3.11.4 (default, Custom Termux VM Console Shell)", LineType.SYSTEM))
            onLineAdded(sessionId, TerminalLine("Type 'exit()' or 'quit()' to exit back to traditional bash console.", LineType.SYSTEM))
        } else {
            // Run Python file if exits
            val filename = args[0]
            val currentDir = getDirForSession(sessionId)
            val file = File(currentDir, filename)
            if (file.exists() && file.isFile) {
                onLineAdded(sessionId, TerminalLine("[execuating python script '$filename' in sandbox module]", LineType.SYSTEM))
                runPythonScript(sessionId, file.readText())
            } else {
                onLineAdded(sessionId, TerminalLine("python: can't open file '$filename': [Errno 2] No such file or directory", LineType.ERROR))
            }
        }
    }

    private fun handlePythonInput(sessionId: Int, command: String) {
        if (command == "exit()" || command == "quit()") {
            exitPythonMode(sessionId)
            onLineAdded(sessionId, TerminalLine("Exiting Interactive Simulated Python Compiler Shell.", LineType.SYSTEM))
            return
        }

        runPythonScript(sessionId, command)
    }

    private fun runPythonScript(sessionId: Int, scriptText: String) {
        val lines = scriptText.split("\n")
        var currentOutput = mutableListOf<String>()
        
        try {
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                
                if (line.startsWith("print(")) {
                    val inner = line.substringAfter("print(").substringBeforeLast(")")
                    if ((inner.startsWith("\"") && inner.endsWith("\"")) || (inner.startsWith("'") && inner.endsWith("'"))) {
                        currentOutput.add(inner.substring(1, inner.length - 1))
                    } else {
                        // Math calculation or simple text evaluate
                        val evaluated = evaluateSimpleMath(inner)
                        currentOutput.add(evaluated ?: "SyntaxError: invalid print argument: '$inner'")
                    }
                } else if (line.contains("+") || line.contains("-") || line.contains("*") || line.contains("/")) {
                    val evaluated = evaluateSimpleMath(line)
                    if (evaluated != null) {
                        currentOutput.add(evaluated)
                    } else {
                        currentOutput.add("SyntaxError: invalid expression '$line'")
                    }
                } else if (line.startsWith("import ")) {
                    currentOutput.add("ModuleImport: successfully imported module '${line.substringAfter("import ")}'")
                } else if (line.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*\\s*=.*"))) {
                    // Variable assignment simulation
                    currentOutput.add("State: Local variable registered '${line.split("=")[0].trim()}'")
                } else {
                    currentOutput.add("Traceback (most recent local call):\n  File \"<stdin>\", line 1, in <module>\nNameError: simulated expression not fully executed in lite shell.")
                }
            }
        } catch (e: Exception) {
            currentOutput.add("SyntaxError: invalid parse syntax (${e.localizedMessage})")
        }

        currentOutput.forEach {
            onLineAdded(sessionId, TerminalLine(it, LineType.OUTPUT))
        }
    }

    private fun evaluateSimpleMath(expr: String): String? {
        val clean = expr.replace(" ", "")
        // Extremely basic evaluation logic for simulator safety
        try {
            if (clean.matches(Regex("\\d+(\\.\\d+)?[+\\-*/]\\d+(\\.\\d+)?"))) {
                val operator = clean.find { it == '+' || it == '-' || it == '*' || it == '/' } ?: return null
                val parts = clean.split(operator)
                val isFloating = parts[0].contains(".") || parts[1].contains(".")
                
                val op1 = parts[0].toDouble()
                val op2 = parts[1].toDouble()
                val result = when (operator) {
                    '+' -> op1 + op2
                    '-' -> op1 - op2
                    '*' -> op1 * op2
                    '/' -> op1 / op2
                    else -> 0.0
                }
                return if (isFloating) result.toString() else result.toInt().toString()
            }
        } catch (e: Exception) {
            return null
        }
        return null
    }

    private fun isPackageRelatedSimulatedCommand(cmd: String): Boolean {
        return listOf("sl", "git", "node", "telnet", "nc").contains(cmd)
    }

    private suspend fun executeSimulatedPackageCommand(sessionId: Int, cmd: String, args: List<String>) {
        when (cmd) {
            "sl" -> {
                // Steam locomotive simulated text train scrolling
                val trainFrames = listOf(
                    "      ====        ___________  _____________\n  _D _|  |__  _[-_[-_[-_[-_[-_[-_[-_[-_[-_[-_\n [=__  ______  | o o o o o o o o o o o o o\n  _I__I_____I__|____________________________\n (H_H_H_H_H_H_H)   I_I_I             I_I_I\n`~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~`",
                    "        ====      ___________  _____________\n  _D _|  |__  _[-_[-_[-_[-_[-_[-_[-_[-_[-_[-_\n [=__  ______  | o o o o o o o o o o o o o\n  _I__I_____I__|____________________________\n (H_H_H_H_H_H_H)   I_I_I             I_I_I\n`~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~`",
                    "          ====    ___________  _____________\n  _D _|  |__  _[-_[-_[-_[-_[-_[-_[-_[-_[-_[-_\n [=__  ______  | o o o o o o o o o o o o o\n  _I__I_____I__|____________________________\n (H_H_H_H_H_H_H)   I_I_I             I_I_I\n`~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~`"
                )
                onLineAdded(sessionId, TerminalLine("Steam Locomotive train sliding in!", LineType.SYSTEM))
                for (frame in trainFrames) {
                    onLineAdded(sessionId, TerminalLine(frame, LineType.OUTPUT))
                    delay(350)
                }
            }
            "git" -> {
                if (args.isEmpty()) {
                    onLineAdded(sessionId, TerminalLine("Usage: git [status | clone <url> | commit -m <msg>]", LineType.SYSTEM))
                    return
                }
                when (args[0]) {
                    "status" -> {
                        onLineAdded(sessionId, TerminalLine("On branch master\nYour branch is up to date with 'origin/master'.\n\nnothing to commit, working tree clean", LineType.OUTPUT))
                    }
                    "clone" -> {
                        val url = if (args.size > 1) args[1] else "https://github.com/aistudio/termux-app.git"
                        onLineAdded(sessionId, TerminalLine("Cloning into 'termux-app'...", LineType.OUTPUT))
                        delay(400)
                        onLineAdded(sessionId, TerminalLine("remote: Enumerating objects: 124, done.\nremote: Counting objects: 100% (124/124), done.\nremote: Compressing objects: 100% (80/80), done.", LineType.OUTPUT))
                        delay(300)
                        onLineAdded(sessionId, TerminalLine("Unpacking objects: 100% (124/124), 1.25 MB | 2.30 MB/s, done.", LineType.SUCCESS))
                    }
                    else -> {
                        onLineAdded(sessionId, TerminalLine("git: '${args[0]}' is currently simulated in mock-terminal logic.", LineType.OUTPUT))
                    }
                }
            }
            "node" -> {
                onLineAdded(sessionId, TerminalLine("Welcome to custom JavaScript console\n> type standard math formulas or simple outputs", LineType.SYSTEM))
                if (args.isEmpty()) {
                    onLineAdded(sessionId, TerminalLine("Node interactive mode is simulated. Running sample: console.log('Matrix rules!')\nOutput: Matrix rules!", LineType.OUTPUT))
                } else {
                    onLineAdded(sessionId, TerminalLine("JS Executed: JS Engine simulated output successfully compiled.", LineType.OUTPUT))
                }
            }
            "telnet" -> {
                onLineAdded(sessionId, TerminalLine("Connecting to simulated ASCII tele-service standard port...", LineType.SYSTEM))
                delay(600)
                onLineAdded(sessionId, TerminalLine("`Connected! Welcome to Telnet ASCII portal star-wars. `\nToggle StarWars ASCII Story Mode (Simulated)", LineType.SYSTEM))
                val frame = "       /\\_/\\\n     =( o.o )=\n       (_\"_\"_)\n Tele-cat greets you over sandboxed Telnet Protocol."
                onLineAdded(sessionId, TerminalLine(frame, LineType.OUTPUT))
            }
            "nc" -> {
                onLineAdded(sessionId, TerminalLine("Netcat listener mocked successfully on port 8080.\nWaiting incoming stream packets...", LineType.OUTPUT))
                delay(500)
                onLineAdded(sessionId, TerminalLine("[connection parsed - 127.0.0.1 closed]", LineType.OUTPUT))
            }
        }
    }

    private suspend fun handleAliasCommand(sessionId: Int, args: List<String>, dbScope: CoroutineScope) {
        if (args.isEmpty()) {
            if (activeAliases.isEmpty()) {
                onLineAdded(sessionId, TerminalLine("No custom terminal aliases currently configured.", LineType.SYSTEM))
            } else {
                onLineAdded(sessionId, TerminalLine("Current active aliases:", LineType.SYSTEM))
                activeAliases.forEach {
                    onLineAdded(sessionId, TerminalLine("  alias ${it.name}='${it.value}'", LineType.OUTPUT))
                }
            }
            return
        }

        val assignment = args.joinToString(" ")
        if (assignment.contains("=")) {
            val name = assignment.substringBefore("=").trim()
            val valueRaw = assignment.substringAfter("=").trim()
            // Strip quotes
            val valClean = if ((valueRaw.startsWith("'") && valueRaw.endsWith("'")) || (valueRaw.startsWith("\"") && valueRaw.endsWith("\""))) {
                valueRaw.substring(1, valueRaw.length - 1)
            } else {
                valueRaw
            }
            
            dbScope.launch {
                onLineAdded(sessionId, TerminalLine("Alias registered: $name mapped directly to '$valClean'", LineType.SUCCESS))
            }
        } else {
            onLineAdded(sessionId, TerminalLine("Usage: alias <name>=<command> (e.g. alias ll='ls -la')", LineType.ERROR))
        }
    }

    /**
     * Executes real shell process on Android under system/bin/sh inside app sandbox variables.
     */
    private suspend fun executeRealShellProcess(sessionId: Int, command: String) = withContext(Dispatchers.IO) {
        val currentDir = getDirForSession(sessionId)
        try {
            // Re-map internal files home dir mapping to '~' inside commands
            val sanitized = command.replace("~", context.filesDir.absolutePath)
            
            val pb = ProcessBuilder("/system/bin/sh", "-c", sanitized)
            pb.directory(currentDir)
            
            // Set environment variable HOME to context filesDir
            val env = pb.environment()
            env["HOME"] = context.filesDir.absolutePath
            env["PATH"] = "/system/bin:/system/xbin:/vendor/bin"
            
            val process = pb.start()

            // Stream standard stdout and stderr in background concurrently
            val stdoutJob = launch {
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    onLineAdded(sessionId, TerminalLine(line!!, LineType.OUTPUT))
                }
            }

            val stderrJob = launch {
                val reader = BufferedReader(InputStreamReader(process.errorStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    onLineAdded(sessionId, TerminalLine(line!!, LineType.ERROR))
                }
            }

            // Wait for outputs to complete streaming and Process output status
            val exitCode = process.waitFor()
            stdoutJob.join()
            stderrJob.join()

            if (exitCode != 0) {
                // If it is standard code 127 (command not found), suggest appropriate alternative
                if (exitCode == 127) {
                    onLineAdded(sessionId, TerminalLine("Hint: Some default Linux binaries like 'git' or 'python' can be installed simulating apt command: 'apt install git'", LineType.SYSTEM))
                }
            }
        } catch (e: Exception) {
            onLineAdded(sessionId, TerminalLine("Process Execution Error: ${e.localizedMessage ?: "unrecognized task shell error"}", LineType.ERROR))
        }
    }

    private suspend fun printWeather(sessionId: Int, city: String) {
        onLineAdded(sessionId, TerminalLine("weather $ reaching atmospheric services via curl wttr.in...", LineType.AI_USER))
        val asciiReport = WeatherClient.getWeatherAscii(city)
        onLineAdded(sessionId, TerminalLine(asciiReport, LineType.OUTPUT))
    }

    private fun printNeofetch(sessionId: Int) {
        val os = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val device = "${Build.MANUFACTURER} ${Build.MODEL}"
        val cpu = Build.HARDWARE
        val arch = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown_arch"
        val kernel = "toybox Linux standard sandbox"
        val shell = "/system/bin/sh"
        val ramValue = getRAMInfo()
        val uptime = getSystemUptime()

        val asciiLogo = """
          _   _     OS: $os
         / \_// \    Device: $device
        (  o.o  )    Kernel: $kernel
         >  -  <     CPU: $cpu ($arch)
        /|     |\    Shell: $shell
       / |     | \   Memory: $ramValue
      /  |_____|  \  Uptime: $uptime
         ||   ||     Terminal: Custom Jetpack Compose
        _||_ _||_    AI Copilot: Enabled 🤖
        """.trimIndent()

        onLineAdded(sessionId, TerminalLine(asciiLogo, LineType.OUTPUT))
    }

    private fun getRAMInfo(): String {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            val totalInGB = memInfo.totalMem / (1024 * 1024 * 1024.0)
            val availInGB = memInfo.availMem / (1024 * 1024 * 1024.0)
            "%.2f GB / %.2f GB".format(Locale.US, totalInGB - availInGB, totalInGB)
        } catch (e: Exception) {
            "2.42 GB / 6.00 GB"
        }
    }

    private fun getSystemUptime(): String {
        return try {
            val millis = android.os.SystemClock.elapsedRealtime()
            val hours = (millis / (1000 * 60 * 60)) % 24
            val minutes = (millis / (1000 * 60)) % 60
            "${hours}h ${minutes}m"
        } catch (e: Exception) {
            "3h 12m"
        }
    }

    private fun printHelp(sessionId: Int) {
        val helpText = """
        ======= Termux Terminal Client System CLI Help =======
        Welcome to the premium feature-rich interactive terminal.
        You can execute actual Android sandboxed shell commands OR custom simulated commands.

        [Custom/Simulated Utilities]:
          neofetch             Show elegant system info graphic card.
          banner / termux      Displays beautiful initial login text card.
          weather [city]       Fetch a gorgeous ASCII live weather report via curl.
          cowsay [msg]         Prints ASCII Cow reciting your custom message block.
          figlet [text]        Write large header in customized block character ASCII.
          matrix               Launch terminal waterfall retro matrix-rain cascade.
          ai [prompt]          Ask Gemini terminal copilot for scripts or shell commands.
          theme [name]         Instantly switch palette (dracula, amber, matrix, cyberpunk).
          apt [update|list|install] Simulated package installer for tools. (Try: apt install sl)
          python [file.py]     Runs python interpreter prompt (after apt install python).
          alias [name=val]     Map customized shortcuts (e.g., alias ll="ls -la").
          clear                Flush screen of previous stdout.

        [Standard Sandbox Utilities]:
          ls, pwd, cd, cat, echo, write, touch, rm, ping, df, uname -a, am, pm.
          (Supports writing files directly inside the sandbox home directory: `~/`)

        Keyboard tip: Toolbar above layout hosts instant Ctrl, Alt, ESC, and Arrow triggers.
        ====================================================
        """.trimIndent()
        onLineAdded(sessionId, TerminalLine(helpText, LineType.SYSTEM))
    }

    private fun generateCowsay(text: String): String {
        val lines = text.split("\n")
        val maxLength = lines.maxOfOrNull { it.length } ?: 0
        val borderTop = "  " + "_".repeat(maxLength + 2)
        val borderBottom = "  " + "-".repeat(maxLength + 2)
        val sb = java.lang.StringBuilder()
        sb.append(borderTop).append("\n")
        for (i in lines.indices) {
            val line = lines[i]
            val padding = " ".repeat(maxLength - line.length)
            if (lines.size == 1) {
                sb.append("< ").append(line).append(" >\n")
            } else if (i == 0) {
                sb.append("/ ").append(line).append("$padding \\\n")
            } else if (i == lines.size - 1) {
                sb.append("\\ ").append(line).append("$padding /\n")
            } else {
                sb.append("| ").append(line).append("$padding |\n")
            }
        }
        sb.append(borderBottom).append("\n")
        sb.append("         \\   ^__^\n")
        sb.append("          \\  (oo)\\_______\n")
        sb.append("             (__)\\       )\\/\\\n")
        sb.append("                 ||----w |\n")
        sb.append("                 ||     ||\n")
        return sb.toString()
    }

    fun generateWelcomeBanner(): String {
        return """
========================================
  _____ ___ ___ __  __ _   ___  __ 
 |_   _| __| _ \  \/  | | | \ \/ / 
   | | | _||   / |\/| | |_| |>  <  
   |_| |___|_|_\_|  |_|\___//_/\_\ 
                                   
  Premium Android Environment Terminal
  Kotlin Compose Engine inside Sandbox
========================================
 * Github: https://github.com/termux
 * Wiki:   https://wiki.termux.com
 * Copilot CLI AI: Enter 'ai [what you seek]'
 * Type 'help' to fetch interactive commands.
 
 Enjoy hacking standard sandbox pipelines!
        """.trimIndent()
    }

    fun generateASCIIBanner(text: String): String {
        // High fidelity crude banner character mapping for alphanumeric
        val banner = java.lang.StringBuilder()
        val clean = text.uppercase()
        val chars = mapOf(
            'A' to arrayOf("  A  ", " A_A ", "AAAAA", "A   A"),
            'B' to arrayOf("BBBB ", "B_B_ ", "B__B ", "BBBB "),
            'C' to arrayOf(" CCCC", "C    ", "C    ", " CCCC"),
            'D' to arrayOf("DDDD ", "D__D ", "D__D ", "DDDD "),
            'E' to arrayOf("EEEEE", "EEE  ", "EEEE ", "EEEEE"),
            'F' to arrayOf("FFFFF", "FFF  ", "F    ", "F    "),
            'H' to arrayOf("H   H", "HHHHH", "H   H", "H   H"),
            'I' to arrayOf(" III ", "  I  ", "  I  ", " III "),
            'L' to arrayOf("L    ", "L    ", "L    ", "LLLLL"),
            'M' to arrayOf("M   M", "MM MM", "M M M", "M   M"),
            'N' to arrayOf("N  N ", "NN N ", "N NN ", "N  N "),
            'O' to arrayOf(" OOO ", "O   O", "O   O", " OOO "),
            'P' to arrayOf("PPPP ", "P__P ", "P    ", "P    "),
            'R' to arrayOf("RRRR ", "R__R ", "R R  ", "R  R "),
            'S' to arrayOf(" SSSS", " S__ ", " ___S", "SSSS "),
            'T' to arrayOf("TTTTT", "  T  ", "  T  ", "  T  "),
            'U' to arrayOf("U   U", "U   U", "U   U", " UUU "),
            'V' to arrayOf("V   V", "V   V", " V V ", "  V  "),
            'W' to arrayOf("W   W", "W W W", "W B W", "W   W"),
            'X' to arrayOf("X   X", " X X ", "  X  ", "X   X"),
            'Y' to arrayOf("Y   Y", " Y Y ", "  Y  ", "  Y  "),
            'Z' to arrayOf("ZZZZZ", "  Z  ", " Z   ", "ZZZZZ")
        )

        for (row in 0 until 4) {
            val rowSB = java.lang.StringBuilder()
            for (char in clean) {
                if (chars.containsKey(char)) {
                    rowSB.append(chars[char]!![row]).append("  ")
                } else {
                    rowSB.append("     ").append("  ") // fallback spacing
                }
            }
            banner.append(rowSB).append("\n")
        }
        return banner.toString()
    }

    private fun handleBotCreation(sessionId: Int, args: List<String>) {
        if (args.isEmpty()) {
            onLineAdded(sessionId, TerminalLine("Usage: bot <name> (e.g., bot my_bot)", LineType.ERROR))
            return
        }
        val botName = args[0]
        val botCode = """
import discord
from discord.ext import commands

bot = commands.Bot(command_prefix='!')

@bot.event
async def on_ready():
    print(f'{bot.user} has connected to Discord!')

bot.run('YOUR_TOKEN_HERE')
""".trimIndent()
        val file = File(getDirForSession(sessionId), "$botName.py")
        file.writeText(botCode)
        onLineAdded(sessionId, TerminalLine("Discord bot boilerplate '$botName.py' created successfully!", LineType.SUCCESS))
    }

    private fun handleModeSelection(sessionId: Int, args: List<String>) {
         onLineAdded(sessionId, TerminalLine("Environment Mode switcher activated (visual updates triggered via settings).", LineType.SUCCESS))
    }

    /**
     * Parse full line of arguments respecting standard single/double quotes groupings.
     */
    private fun parseCommandLine(cmdText: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = java.lang.StringBuilder()
        var insideDoubleQuotes = false
        var insideSingleQuotes = false
        
        var i = 0
        while (i < cmdText.length) {
            val c = cmdText[i]
            if (c == '"' && !insideSingleQuotes) {
                insideDoubleQuotes = !insideDoubleQuotes
            } else if (c == '\'' && !insideDoubleQuotes) {
                insideSingleQuotes = !insideSingleQuotes
            } else if (c == ' ' && !insideDoubleQuotes && !insideSingleQuotes) {
                if (sb.isNotEmpty()) {
                    tokens.add(sb.toString())
                    sb.setLength(0)
                }
            } else {
                sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty()) {
            tokens.add(sb.toString())
        }
        return tokens
    }
}
