package com.hippo.ehviewer.translation.engine


class TranslationOutputLimitException @JvmOverloads constructor(
    message: String = "Translation output limit reached",
    val usage: Usage? = null,
) : IllegalStateException(message)
