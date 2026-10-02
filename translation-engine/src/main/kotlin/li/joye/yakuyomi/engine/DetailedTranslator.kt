package li.joye.yakuyomi.engine

/** Per-call metadata, without relying on a translator's mutable last-result fields. */
interface DetailedTranslator : Translator {
    suspend fun translateDetailed(queries: List<String>): LlmTranslator.TranslateResult

    override suspend fun translate(queries: List<String>): List<String> = translateDetailed(queries).translations
}
