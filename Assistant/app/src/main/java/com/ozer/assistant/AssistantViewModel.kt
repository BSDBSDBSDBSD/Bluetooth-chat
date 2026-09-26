package com.ozer.assistant

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ozer.assistant.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Message(val fromUser: Boolean, val text: String, val debug: String = "")

class AssistantViewModel(app: Application) : AndroidViewModel(app) {
    val store = Store.get(app)
    val messages = mutableStateListOf(Message(false, "שלום! אני העוזר שלך, ואני עובד בלי אינטרנט. במה אפשר לעזור?"))
    val busy = mutableStateOf(false)

    /** Permissions the last command needs; the UI asks for them and then calls [retryPending]. */
    val permissionRequest = mutableStateOf<List<String>>(emptyList())
    private var pending: String? = null

    /** Last reply, for text-to-speech. */
    val lastReply = mutableStateOf<Pair<Long, String>?>(null)

    private var assistant: Assistant? = null

    private suspend fun brain(): Assistant = assistant ?: withContext(Dispatchers.Default) {
        Assistant(getApplication()).also { assistant = it }
    }

    init {
        viewModelScope.launch { brain() }
    }

    fun send(text: String, echo: Boolean = true) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (echo) messages += Message(true, t)
        busy.value = true
        viewModelScope.launch {
            val reply = withContext(Dispatchers.Default) {
                try {
                    brain().handle(t)
                } catch (e: Exception) {
                    Reply("משהו השתבש: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            messages += Message(false, reply.text, reply.debug)
            lastReply.value = System.nanoTime() to reply.text
            if (reply.permissions.isNotEmpty()) {
                pending = if (reply.retry) t else null
                permissionRequest.value = reply.permissions
            }
            busy.value = false
        }
    }

    fun onPermissionsResult(granted: Boolean) {
        permissionRequest.value = emptyList()
        val p = pending
        pending = null
        if (granted && p != null) send(p, echo = false)
    }
}
