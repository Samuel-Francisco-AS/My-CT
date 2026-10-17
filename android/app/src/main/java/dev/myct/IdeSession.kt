package dev.myct

import androidx.compose.runtime.*
import java.io.File

/** Shared presentation contract, not a filesystem abstraction. SFTP keeps its R4 transaction. */
internal interface IdeSession {
    val scroll: IdeScrollHistory
    val connected: Boolean
    val busy: Boolean
    val listing: RemoteListing?
    val document: IdeDocument?
    var browse: Boolean
    val message: String
    val noticeIsError: Boolean
    val canSave: Boolean
    fun loadDirectory(path: String? = null)
    fun open(path: String)
    fun save()
    fun closeDocument()
    fun cancel()
    fun child(parent: String, name: String): String
    fun fileName(path: String): String = path.substringAfterLast('/')
    fun entryPath(parent: String, entry: RemoteEntry): String = child(parent, entry.name)
    fun parent(path: String): String
    fun atRoot(path: String): Boolean
    fun displayPath(path: String): String
}

/** A visible row captures its opaque target before LazyColumn schedules keys/callbacks. */
internal data class IdeEntryRow(val entry: RemoteEntry, val reference: String)
internal fun IdeSession.rows(value: RemoteListing?): List<IdeEntryRow> =
    value?.entries?.map { IdeEntryRow(it, entryPath(value.path, it)) }.orEmpty()

internal enum class IdeEnvironment(val label: String) { LOCAL("Local · Moto G06"), REMOTE("Remoto · Fedora") }

internal enum class IdeStorage(val label: String) { PRIVATE("Projetos privados"), SHARED("Pasta compartilhada") }

/** Both sessions live as long as TerminalModel, including while their view is absent. */
internal fun restoredIdeEnvironment(preferred: IdeEnvironment, remoteAvailable: Boolean) =
    if (preferred == IdeEnvironment.REMOTE && remoteAvailable) preferred else IdeEnvironment.LOCAL

internal class IdeModel(base: File,
                       val local: LocalIdeModel = LocalIdeModel(LocalWorkspace(base)),
                       val remote: RemoteIdeModel = RemoteIdeModel(),
                       preferred: IdeEnvironment = IdeEnvironment.LOCAL,
                       val shared: SharedIdeModel? = null,
                       preferredStorage: IdeStorage = IdeStorage.PRIVATE,
                       private val rememberStorage: (IdeStorage) -> Unit = {},
                       private val rememberMode: (IdeEnvironment) -> Unit = {}) {
    // A new process has no authenticated SSH session: safe fallback even if last choice was Remote.
    var environment by mutableStateOf(restoredIdeEnvironment(preferred, remote.connected)); private set
    var storage by mutableStateOf(if (shared != null) preferredStorage else IdeStorage.PRIVATE); private set
    fun selectStorage(value: IdeStorage) {
        if (shared?.busy == true || local.busy || (value == IdeStorage.SHARED && shared == null)) return
        storage = value; rememberStorage(value)
    }
    val session: IdeSession get() = if (environment == IdeEnvironment.REMOTE) remote
        else if (storage == IdeStorage.SHARED) requireNotNull(shared) else local
    val hasDirtyBuffers get() = local.document?.dirty == true || remote.document?.dirty == true || shared?.document?.dirty == true
    fun select(value: IdeEnvironment) { environment = value; rememberMode(value) }
    fun attach(value: SshConnection?, remoteIdentity: String? = null) {
        if (remoteIdentity == null) remote.attach(value) else remote.attach(value, remoteIdentity)
    }
}
