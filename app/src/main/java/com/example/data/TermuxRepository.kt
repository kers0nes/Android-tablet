package com.example.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class TermuxRepository(private val dao: TermuxDao) {

    val allSessions: Flow<List<TerminalSession>> = dao.getAllSessions()
    val commandHistory: Flow<List<CommandHistory>> = dao.getCommandHistory()
    val allAliases: Flow<List<ShellAlias>> = dao.getAllAliases()
    val installedPackages: Flow<List<InstalledPackage>> = dao.getInstalledPackagesFlow()

    suspend fun insertSession(session: TerminalSession): Int = withContext(Dispatchers.IO) {
        val newId = dao.insertSession(session)
        newId.toInt()
    }

    suspend fun updateSession(session: TerminalSession) = withContext(Dispatchers.IO) {
        dao.updateSession(session)
    }

    suspend fun deleteSession(session: TerminalSession) = withContext(Dispatchers.IO) {
        dao.deleteSession(session)
    }

    suspend fun activateSession(sessionId: Int) = withContext(Dispatchers.IO) {
        dao.deactivateAllSessions()
        dao.activateSession(sessionId)
    }

    suspend fun addHistory(commandStr: String) = withContext(Dispatchers.IO) {
        dao.insertHistory(CommandHistory(command = commandStr))
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        dao.clearHistory()
    }

    suspend fun addAlias(name: String, value: String) = withContext(Dispatchers.IO) {
        dao.insertAlias(ShellAlias(name = name, value = value))
    }

    suspend fun deleteAlias(name: String) = withContext(Dispatchers.IO) {
        dao.deleteAlias(name)
    }

    suspend fun installPackage(packageName: String) = withContext(Dispatchers.IO) {
        dao.installPackage(InstalledPackage(packageName = packageName))
    }

    suspend fun uninstallPackage(packageName: String) = withContext(Dispatchers.IO) {
        dao.uninstallPackage(packageName)
    }

    suspend fun getInstalledPackagesList(): List<InstalledPackage> = withContext(Dispatchers.IO) {
        dao.getInstalledPackages()
    }
}
