package com.hippo.ehviewer.gallery

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.hippo.lib.yorozuya.FileUtils
import com.hippo.unifile.UniFile
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.*

/** Event-maintained index of complete result images, keyed by source page rather than inference runs. */
object GalleryProcessingProgress {
    enum class Kind { TRANSLATION, SUPER_RESOLUTION }
    fun interface Listener { fun onChanged(galleryId: Long) }
    data class Counts(val translated: Int, val enhanced: Int) {
        fun primary(pages: Int): String? = when {
            translated.coerceAtMost(pages) > 0 -> "TL ${translated.coerceAtMost(pages)}/$pages"
            enhanced.coerceAtMost(pages) > 0 -> "SR ${enhanced.coerceAtMost(pages)}/$pages"
            else -> null
        }
        fun secondary(pages: Int): String? = if (translated > 0 && enhanced > 0 && pages > 0)
            "SR ${enhanced.coerceAtMost(pages)}/$pages" else null
        fun format(readPage: Int, pages: Int): String =
            "${readPage.coerceIn(1, pages.coerceAtLeast(1))}/$pages" + (primary(pages)?.let { "  -  $it" } ?: "")
    }

    private const val PREFERENCES = "gallery_processing_progress_v1"
    // Each task runs to completion without suspension: deletion and publication events stay ordered.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val main = Handler(Looper.getMainLooper())
    private var helper: SQLiteOpenHelper? = null

    // Keep the per-page URI index out of SharedPreferences: a page update must not rewrite every gallery.
    // This helper is only opened/accessed on the serial IO dispatcher.
    private fun database(context: Context): SQLiteDatabase {
        val existing = helper ?: object : SQLiteOpenHelper(context.applicationContext,
            "gallery_processing_progress.db", null, 2) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE results (gallery_id INTEGER NOT NULL, source_key TEXT NOT NULL, " +
                    "uri TEXT NOT NULL, PRIMARY KEY (gallery_id, source_key))")
                db.execSQL("CREATE INDEX results_uri ON results(uri)")
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                if (oldVersion < 2) db.execSQL("CREATE INDEX IF NOT EXISTS results_uri ON results(uri)")
            }
        }.also { helper = it }
        return existing.writableDatabase
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** Only two cached integers are read while binding a download row. */
    @JvmStatic fun get(context: Context, galleryId: Long): Counts = preferences(context).let {
        Counts(it.getInt("$galleryId.t", 0), it.getInt("$galleryId.s", 0))
    }

    @JvmStatic fun addListener(listener: Listener) { listeners.add(listener) }
    @JvmStatic fun removeListener(listener: Listener) { listeners.remove(listener) }

    internal fun sourceKey(name: String): String = name.replace('\\', '/').split('/')
        .filter { it.isNotEmpty() }.joinToString("/") {
            require(it != "." && it != "..")
            var part = FileUtils.sanitizeFilename(it).trimEnd('.')
            while (part.toByteArray(Charsets.UTF_8).size > 220)
                part = part.substring(0, part.offsetByCodePoints(part.length, -1))
            part.ifBlank { "page" }
        }.ifEmpty { "page" }

    /** Called only after publishing/removing a result. Partial translations and skips pass null. */
    internal fun record(context: Context, galleryId: Long, kind: Kind, sourceName: String, result: UniFile?): Job {
        val app = context.applicationContext
        val key = "${kind.name}/${sourceKey(sourceName)}"
        val uri = result?.uri?.toString()
        return scope.launch {
            runCatching {
                val db = database(app)
                val previous = db.query("results", arrayOf("uri"), "gallery_id=? AND source_key=?",
                    arrayOf(galleryId.toString(), key), null, null, null).use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
                if (previous == uri) return@launch
                if (uri == null) db.delete("results", "gallery_id=? AND source_key=?", arrayOf(galleryId.toString(), key))
                else put(db, galleryId, key, uri)
                saveSnapshot(app, galleryId, db)
            }.onFailure { android.util.Log.w("ProcessingProgress", "Cannot index result", it) }
        }
    }

    /** The storage owner supplies the files it actually removed; no directory or image reads. */
    internal fun forget(context: Context, uris: Collection<Uri>): Job {
        val removed = uris.map { it.toString() }.distinct()
        return update(context) { db ->
            val affected = mutableSetOf<Long>()
            for (batch in removed.chunked(400)) {
                affected += deleteWhere(db, "uri IN (${batch.joinToString(",") { "?" }})", batch.toTypedArray())
            }
            affected
        }
    }

    /** Used when a whole SR tile page or cache root was deleted successfully. */
    internal fun forgetUnder(context: Context, root: Uri): Job {
        val path = root.toString().trimEnd('/')
        val prefix = "$path/"
        return update(context) { db ->
            deleteWhere(db, "uri=? OR (uri>=? AND uri<?)", arrayOf(path, prefix, "${path}0"))
        }
    }

    /** A failed bulk delete may leave results behind. Check only its already indexed entries. */
    internal fun removeMissing(context: Context, galleryIds: Set<Long>, kind: Kind): Job = update(context) { db ->
        val affected = mutableSetOf<Long>()
        for (gid in galleryIds) {
            val missing = mutableListOf<String>()
            db.query("results", arrayOf("source_key", "uri"), "gallery_id=? AND source_key LIKE ?",
                arrayOf(gid.toString(), "${kind.name}/%"), null, null, null).use { cursor ->
                while (cursor.moveToNext()) {
                    val file = UniFile.fromUri(context, Uri.parse(cursor.getString(1)))
                    if (file != null && !file.exists()) missing += cursor.getString(0)
                }
            }
            for (key in missing) db.delete("results", "gallery_id=? AND source_key=?", arrayOf(gid.toString(), key))
            if (missing.isNotEmpty()) affected += gid
        }
        affected
    }

    private fun update(context: Context, action: (SQLiteDatabase) -> Set<Long>): Job {
        val app = context.applicationContext
        return scope.launch {
            runCatching {
                val db = database(app)
                db.beginTransaction()
                val affected = try { action(db).also { db.setTransactionSuccessful() } }
                finally { db.endTransaction() }
                for (gid in affected) saveSnapshot(app, gid, db)
            }.onFailure { android.util.Log.w("ProcessingProgress", "Cannot remove indexed results", it) }
        }
    }

    private fun deleteWhere(db: SQLiteDatabase, where: String, args: Array<String>): Set<Long> {
        val affected = mutableSetOf<Long>()
        db.query("results", arrayOf("gallery_id"), where, args, null, null, null).use {
            while (it.moveToNext()) affected += it.getLong(0)
        }
        if (affected.isNotEmpty()) db.delete("results", where, args)
        return affected
    }

    @JvmStatic fun remove(context: Context, galleryId: Long) {
        val app = context.applicationContext
        scope.launch {
            database(app).delete("results", "gallery_id=?", arrayOf(galleryId.toString()))
            preferences(app).edit().remove("$galleryId.t").remove("$galleryId.s").apply()
        }
    }

    private fun put(db: SQLiteDatabase, galleryId: Long, key: String, uri: String) {
        db.insertWithOnConflict("results", null, ContentValues().apply {
            put("gallery_id", galleryId); put("source_key", key); put("uri", uri)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "Cannot index result" } }
    }

    private fun saveSnapshot(context: Context, galleryId: Long, db: SQLiteDatabase) {
        val counts = db.rawQuery("SELECT SUM(source_key LIKE 'TRANSLATION/%'), " +
            "SUM(source_key LIKE 'SUPER_RESOLUTION/%') FROM results WHERE gallery_id=?",
            arrayOf(galleryId.toString())).use { cursor ->
            cursor.moveToFirst(); Counts(cursor.getInt(0), cursor.getInt(1))
        }
        val changed = get(context, galleryId) != counts
        if (!changed) return
        preferences(context).edit().putInt("$galleryId.t", counts.translated)
            .putInt("$galleryId.s", counts.enhanced).apply()
        main.post { listeners.forEach { it.onChanged(galleryId) } }
    }

}
