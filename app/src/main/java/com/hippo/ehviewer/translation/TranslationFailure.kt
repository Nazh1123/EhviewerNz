package com.hippo.ehviewer.translation

import android.content.Context
import androidx.annotation.StringRes
import com.hippo.ehviewer.AppLanguage
import com.hippo.ehviewer.R
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal sealed interface TranslationFailure {
    @get:StringRes val detail: Int
    val arguments: Array<out Any>
}

internal class TranslationRequestFailure(
    @param:StringRes override val detail: Int,
    diagnostic: String,
    vararg override val arguments: Any,
) : IOException(diagnostic), TranslationFailure

internal class TranslationModelFailure(
    @param:StringRes override val detail: Int,
    diagnostic: String,
    vararg override val arguments: Any,
) : IllegalStateException(diagnostic), TranslationFailure

internal class TranslationInputFailure(
    @param:StringRes override val detail: Int,
    diagnostic: String,
) : IllegalArgumentException(diagnostic), TranslationFailure {
    override val arguments = arrayOf<Any>()
}

internal fun modelVerificationFailure(name: String, diagnostic: String) =
    TranslationModelFailure(R.string.translation_error_model_integrity, diagnostic, name)

internal fun translationFailureMessage(context: Context, @StringRes summary: Int, error: Exception): String {
    val strings = AppLanguage.context(context)
    // Exception messages can contain raw SDK text, server data or filesystem paths.
    val detail = when (error) {
        is TranslationFailure -> strings.getString(error.detail, *error.arguments)
        is SocketTimeoutException, is SocketException, is UnknownHostException ->
            strings.getString(R.string.translation_error_network)
        is FileNotFoundException, is SecurityException ->
            strings.getString(R.string.translation_error_storage)
        else -> strings.getString(R.string.translation_error_retry)
    }
    return strings.getString(summary, detail)
}
