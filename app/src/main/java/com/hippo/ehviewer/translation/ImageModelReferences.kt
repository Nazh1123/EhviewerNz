package com.hippo.ehviewer.translation

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

/** One atomic record per model set; URI grants can be shared by multiple stores. */
internal class ImageModelReferences(private val context: Context, directoryName: String) {
    data class Reference(val uri: Uri, val stamp: String)
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val key = directoryName

    fun all(): Map<String, Reference> {
        val json = JSONObject(preferences.getString(key, "{}") ?: "{}")
        return json.keys().asSequence().associateWith { name ->
            val item = json.getJSONObject(name)
            Reference(Uri.parse(item.getString("uri")), item.getString("stamp"))
        }
    }

    fun replace(references: Map<String, Reference>) {
        val json = JSONObject()
        references.forEach { (name, reference) ->
            json.put(name, JSONObject().put("uri", reference.uri.toString()).put("stamp", reference.stamp))
        }
        val editor = preferences.edit()
        if (references.isEmpty()) editor.remove(key) else editor.putString(key, json.toString())
        check(editor.commit()) { "Cannot save image model references" }
    }

    fun releaseUnused(uri: Uri) {
        if (uri.scheme != "content" || uses(context, uri) ||
            context.getSharedPreferences("translation_model_names", Context.MODE_PRIVATE).all.values.any { it == uri.toString() }) return
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    companion object {
        private const val PREFERENCES = "translation_image_references"
        fun uses(context: Context, uri: Uri): Boolean =
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).all.values.any { value ->
                val json = JSONObject(value as String)
                json.keys().asSequence().any { json.getJSONObject(it).getString("uri") == uri.toString() }
            }
    }
}
