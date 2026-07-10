package ovh.battistella.ondes.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide channel for transient snackbars, including reversible "undo" actions.
 * Any ViewModel or manager can [show] a message; the root scaffold collects
 * [messages] and renders them on a single shared [androidx.compose.material3.SnackbarHost].
 */
@Singleton
class SnackbarController @Inject constructor() {

    // An application-lifetime scope for undo work. A ViewModel's own scope is
    // cancelled the moment its screen is popped, so an "Undo" tapped right after
    // navigating back would silently do nothing (issue P1-19); running it here
    // decouples the reversal from the originating screen. It uses the main
    // dispatcher (like a viewModelScope) — the repository calls it launches switch
    // to IO themselves — which also keeps it drivable by the test scheduler.
    private val undoScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    data class Message(
        val text: String,
        val actionLabel: String? = null,
        val onAction: (() -> Unit)? = null,
        /** Undo prompts linger longer so the reversal is actually reachable. */
        val isUndo: Boolean = false,
    )

    // A buffer so emissions from non-suspending callers (tryEmit) aren't dropped
    // when a few land back-to-back before the collector resumes.
    private val _messages = MutableSharedFlow<Message>(extraBufferCapacity = 16)
    val messages = _messages.asSharedFlow()

    fun show(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        _messages.tryEmit(Message(text, actionLabel, onAction))
    }

    /**
     * Show a reversible message whose [action] runs in an application-scoped
     * coroutine, so it survives the originating screen being navigated away from.
     */
    fun showUndo(text: String, actionLabel: String, action: suspend () -> Unit) {
        _messages.tryEmit(
            Message(
                text = text,
                actionLabel = actionLabel,
                onAction = { undoScope.launch { action() } },
                isUndo = true,
            ),
        )
    }
}
