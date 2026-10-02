package li.joye.yakuyomi.engine

/** A generation budget was exhausted. Other inference/transport failures must not trigger splitting. */
class TranslationOutputLimitException @JvmOverloads constructor(
    message: String = "Translation output limit reached",
    val usage: Usage? = null,
) : IllegalStateException(message)
