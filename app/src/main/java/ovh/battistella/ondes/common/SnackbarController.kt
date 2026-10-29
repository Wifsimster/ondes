package ovh.battistella.ondes.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SnackbarController @Inject constructor() {

    private val undoScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    data class Message(
        val text: String,
        val actionLabel: String? = null,
        val onAction: (() -> Unit)? = null,
        val isUndo: Boolean = false,
    )

    private val _messages = Channel<Message>(capacity = 16)
    val messages: Flow<Message> = _messages.receiveAsFlow()

    fun show(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        _messages.trySend(Message(text, actionLabel, onAction))
    }

    fun showUndo(text: String, actionLabel: String, action: suspend () -> Unit) {
        _messages.trySend(
            Message(
                text = text,
                actionLabel = actionLabel,
                onAction = { undoScope.launch { action() } },
                isUndo = true,
            ),
        )
    }
}
