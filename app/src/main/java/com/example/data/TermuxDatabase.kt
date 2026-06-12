package com.example.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "terminal_sessions")
data class TerminalSession(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val isActive: Boolean = false
)

@Entity(tableName = "command_history")
data class CommandHistory(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val command: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "shell_aliases")
data class ShellAlias(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val value: String
)

@Entity(tableName = "installed_packages")
data class InstalledPackage(
    @PrimaryKey val packageName: String,
    val installedAt: Long = System.currentTimeMillis()
)

@Dao
interface TermuxDao {
    // Sessions
    @Query("SELECT * FROM terminal_sessions ORDER BY id ASC")
    fun getAllSessions(): Flow<List<TerminalSession>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: TerminalSession): Long

    @Update
    suspend fun updateSession(session: TerminalSession)

    @Delete
    suspend fun deleteSession(session: TerminalSession)

    @Query("UPDATE terminal_sessions SET isActive = 0")
    suspend fun deactivateAllSessions()

    @Query("UPDATE terminal_sessions SET isActive = 1 WHERE id = :sessionId")
    suspend fun activateSession(sessionId: Int)

    // Command History
    @Query("SELECT * FROM command_history ORDER BY id DESC LIMIT 100")
    fun getCommandHistory(): Flow<List<CommandHistory>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: CommandHistory)

    @Query("DELETE FROM command_history")
    suspend fun clearHistory()

    // Aliases
    @Query("SELECT * FROM shell_aliases")
    fun getAllAliases(): Flow<List<ShellAlias>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlias(alias: ShellAlias)

    @Query("DELETE FROM shell_aliases WHERE name = :name")
    suspend fun deleteAlias(name: String)

    // Packages
    @Query("SELECT * FROM installed_packages")
    fun getInstalledPackagesFlow(): Flow<List<InstalledPackage>>

    @Query("SELECT * FROM installed_packages")
    suspend fun getInstalledPackages(): List<InstalledPackage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun installPackage(pkg: InstalledPackage)

    @Query("DELETE FROM installed_packages WHERE packageName = :name")
    suspend fun uninstallPackage(name: String)
}

@Database(
    entities = [
        TerminalSession::class,
        CommandHistory::class,
        ShellAlias::class,
        InstalledPackage::class
    ],
    version = 1,
    exportSchema = false
)
abstract class TermuxDatabase : RoomDatabase() {
    abstract fun termuxDao(): TermuxDao

    companion object {
        @Volatile
        private var INSTANCE: TermuxDatabase? = null

        fun getDatabase(context: Context): TermuxDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TermuxDatabase::class.java,
                    "termux_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
