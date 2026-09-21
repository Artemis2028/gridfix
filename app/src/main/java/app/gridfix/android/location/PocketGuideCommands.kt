package app.gridfix.android.location

/** Receives queued service commands without letting old data change a newer guide. */
internal class PocketGuideCommands {
    var runId: Long? = null
        private set
    private var targetId: String? = null
    private var lastSequence = 0L

    fun start(runId: Long, targetId: String) {
        this.runId = runId
        this.targetId = targetId
        lastSequence = 0L
    }

    fun update(runId: Long, sequence: Long, expectedTargetId: String, targetId: String, retarget: Boolean): Boolean {
        if (this.runId != runId || sequence <= lastSequence ||
            (!retarget && this.targetId != expectedTargetId)
        ) return false
        this.targetId = targetId
        lastSequence = sequence
        return true
    }

    fun canStop(runId: Long, sequence: Long, expectedTargetId: String): Boolean =
        this.runId == runId && this.targetId == expectedTargetId && sequence > lastSequence

    fun clear() {
        runId = null
        targetId = null
        lastSequence = 0L
    }
}
