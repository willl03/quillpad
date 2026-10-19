package org.qosp.notes.ui.utils

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

class Toaster(private val context: Context) {
    private val _messages: MutableSharedFlow<Pair<String, Int>> = MutableSharedFlow()
    private var lastMessage = ""

    val messages: Flow<Pair<String, Int>> = _messages

    fun showShort(message: String) = sendChecking(message) { _messages.tryEmit(message to Toast.LENGTH_SHORT) }

    fun showShort(@StringRes messageRes: Int) = showShort(context.getString(messageRes))

    fun showShort(@StringRes messageRes: Int, vararg args: Any) =
        showShort(context.getString(messageRes, *args))

    fun showLong(message: String) = sendChecking(message) { _messages.tryEmit(message to Toast.LENGTH_LONG) }

    fun showLong(@StringRes messageRes: Int) = showLong(context.getString(messageRes))

    fun showLong(@StringRes messageRes: Int, vararg args: Any) =
        showLong(context.getString(messageRes, *args))

    private fun sendChecking(message: String, sendFunc: () -> Unit) {
        if (message != lastMessage && message.isNotEmpty()) {
            lastMessage = message
            sendFunc()
        }
    }
}
