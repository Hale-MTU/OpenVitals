package tech.mmarca.openvitals.data.repository.contract

/**
 * Told after this app deleted an activity from Health Connect, with the deleted session's id.
 * It must return at once and never throw: deleting has already happened, and anything slow
 * (a network call) belongs on the listener's own scope. The default build binds none
 * (`di/ExtensionsModule`). The opt-in VertMatch build binds one that deletes the copy it sent.
 */
fun interface ActivityDeletionListener {
    fun onActivityDeleted(id: String)
}
