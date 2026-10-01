package moe.hellowidget.sync

import android.content.Context
import moe.hellowidget.R

/** 把 [WebDavError] 映射成本地化文案（通知与同步页共用一份，避免两处不一致） */
object SyncErrorText {

    fun of(context: Context, error: WebDavError): String = context.getString(resOf(error))

    /** 从持久化的错误名还原文案（状态为 failed 时用） */
    fun ofName(context: Context, name: String?): String? {
        val error = name?.let { runCatching { WebDavError.valueOf(it) }.getOrNull() } ?: return null
        return of(context, error)
    }

    fun resOf(error: WebDavError): Int = when (error) {
        WebDavError.NOT_CONFIGURED -> R.string.sync_error_not_configured
        WebDavError.UNAUTHORIZED -> R.string.sync_error_unauthorized
        WebDavError.FORBIDDEN -> R.string.sync_error_forbidden
        WebDavError.NOT_FOUND -> R.string.sync_error_not_found
        WebDavError.PARENT_NOT_FOUND -> R.string.sync_error_parent_not_found
        WebDavError.NOT_SUPPORTED -> R.string.sync_error_not_supported
        WebDavError.PRECONDITION_FAILED -> R.string.sync_error_precondition
        WebDavError.LOCKED -> R.string.sync_error_locked
        WebDavError.INSUFFICIENT_STORAGE -> R.string.sync_error_storage
        WebDavError.SERVER_ERROR -> R.string.sync_error_server
        WebDavError.REDIRECT -> R.string.sync_error_redirect
        WebDavError.NETWORK -> R.string.sync_error_network
        WebDavError.TIMEOUT -> R.string.sync_error_timeout
        WebDavError.TLS_UNTRUSTED -> R.string.sync_error_tls_untrusted
        WebDavError.TLS -> R.string.sync_error_tls
        WebDavError.TOO_LARGE -> R.string.sync_error_too_large
        WebDavError.BAD_RESPONSE -> R.string.sync_error_bad_response
        WebDavError.IO -> R.string.sync_error_io
    }
}
