package app.filenfiles.transfer

import app.filenfiles.FilesApp
import app.filenfiles.core.CopyEngine
import app.filenfiles.core.CopyLedger
import app.filenfiles.core.ItemId
import app.filenfiles.filen.FailureKind
import app.filenfiles.filen.failureKind

/** [CopyEngine] wired to the app's storages. Only used from [TransferManager], which owns queueing and persistence. */
class CopyRunner(
    private val app: FilesApp,
    private val ledger: CopyLedger = CopyLedger(),
    private val setTotal: (Long) -> Unit,
    private val setDone: (Long) -> Unit,
    private val setWaiting: (Boolean) -> Unit,
    private val awaitNetwork: suspend () -> Boolean,
) {
    suspend fun run(source: ItemId, destFolder: ItemId, move: Boolean) =
        CopyEngine(app.router, ledger, setTotal, setDone, setWaiting, { it.failureKind() == FailureKind.NETWORK }, awaitNetwork)
            .run(source, destFolder, move)
}
