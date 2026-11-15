package com.docscanai.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

// Single session DataStore for the whole process (one per file name).
private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "docscan_session")

/**
 * Lightweight persistence for the active [ScanDocument] so captured pages survive rotation and
 * process death (TECHNICAL_SPEC.md §6.2, ARCHITECTURE.md §6/§8).
 *
 * The [ScanDocument] metadata (session id, page ids, file paths, corners, order, rotation and
 * enhancement settings) is serialized to JSON and stored as a single DataStore(Preferences)
 * string. The page image/thumbnail files themselves live in app-private storage
 * (`filesDir/scans/<sessionId>/`) and are only referenced by path here.
 */
class SessionStore(private val context: Context) {

    private val documentKey = stringPreferencesKey("scan_document")

    /** Persists the given [document], overwriting any previous snapshot. */
    suspend fun save(document: ScanDocument) {
        runCatching {
            context.sessionDataStore.edit { prefs -> prefs[documentKey] = encode(document) }
        }
    }

    /** Loads the last persisted [ScanDocument], or null when none exists / is corrupt. */
    suspend fun load(): ScanDocument? {
        val prefs = runCatching { context.sessionDataStore.data.first() }.getOrNull() ?: return null
        val json = prefs[documentKey] ?: return null
        return runCatching { decode(json) }.getOrNull()
    }

    /** Clears the persisted snapshot. */
    suspend fun clear() {
        runCatching { context.sessionDataStore.edit { it.remove(documentKey) } }
    }

    // --- JSON (org.json) -------------------------------------------------------------------

    private fun encode(document: ScanDocument): String {
        val root = JSONObject()
        root.put("sessionId", document.sessionId)
        root.put("enhancementMode", document.enhancementMode.id)
        val pages = JSONArray()
        document.pages.forEach { page ->
            val p = JSONObject()
            p.put("id", page.id)
            p.put("imageFile", page.imageFile)
            p.put("rawFile", page.rawFile ?: JSONObject.NULL)
            p.put("thumbnailFile", page.thumbnailFile ?: JSONObject.NULL)
            p.put("order", page.order)
            p.put("rotationDegrees", page.rotationDegrees)
            page.corners?.let { p.put("corners", encodeQuad(it)) }
            p.put("enhancement", encodeEnhancement(page.enhancement))
            p.put("handwritingSuggested", page.handwritingSuggested)
            pages.put(p)
        }
        root.put("pages", pages)
        return root.toString()
    }

    private fun decode(json: String): ScanDocument {
        val root = JSONObject(json)
        val pagesArray = root.optJSONArray("pages") ?: JSONArray()
        val pages = ArrayList<ScannedPage>(pagesArray.length())
        for (i in 0 until pagesArray.length()) {
            val p = pagesArray.getJSONObject(i)
            pages.add(
                ScannedPage(
                    id = p.getString("id"),
                    imageFile = p.getString("imageFile"),
                    rawFile = if (p.isNull("rawFile")) null else p.getString("rawFile"),
                    thumbnailFile = if (p.isNull("thumbnailFile")) null else p.getString("thumbnailFile"),
                    corners = if (p.has("corners")) decodeQuad(p.getJSONObject("corners")) else null,
                    order = p.optInt("order", i),
                    rotationDegrees = p.optInt("rotationDegrees", 0),
                    enhancement = if (p.has("enhancement")) {
                        decodeEnhancement(p.getJSONObject("enhancement"))
                    } else {
                        PageEnhanceSettings()
                    },
                    handwritingSuggested = p.optBoolean("handwritingSuggested", false)
                )
            )
        }
        return ScanDocument(
            sessionId = root.optString("sessionId").ifBlank { UUID.randomUUID().toString() },
            pages = pages,
            enhancementMode = EnhancementMode.fromId(root.optString("enhancementMode"))
        )
    }

    private fun encodeQuad(quad: Quad): JSONObject = JSONObject().apply {
        put("topLeft", encodePoint(quad.topLeft))
        put("topRight", encodePoint(quad.topRight))
        put("bottomRight", encodePoint(quad.bottomRight))
        put("bottomLeft", encodePoint(quad.bottomLeft))
    }

    private fun decodeQuad(o: JSONObject): Quad = Quad(
        topLeft = decodePoint(o.getJSONObject("topLeft")),
        topRight = decodePoint(o.getJSONObject("topRight")),
        bottomRight = decodePoint(o.getJSONObject("bottomRight")),
        bottomLeft = decodePoint(o.getJSONObject("bottomLeft"))
    )

    private fun encodePoint(point: Point): JSONObject = JSONObject().apply {
        put("x", point.x.toDouble())
        put("y", point.y.toDouble())
    }

    private fun decodePoint(o: JSONObject): Point =
        Point(o.getDouble("x").toFloat(), o.getDouble("y").toFloat())

    private fun encodeEnhancement(settings: PageEnhanceSettings): JSONObject = JSONObject().apply {
        put("mode", settings.mode.id)
        put("gamma", settings.gamma.toDouble())
        put("contrast", settings.contrast.toDouble())
        put("shadowRemove", settings.shadowRemove)
        put("binarize", settings.binarize)
        put("handwriting", settings.handwriting)
    }

    private fun decodeEnhancement(o: JSONObject): PageEnhanceSettings = PageEnhanceSettings(
        mode = EnhancementMode.fromId(o.optString("mode")),
        gamma = o.optDouble("gamma", 1.0).toFloat(),
        contrast = o.optDouble("contrast", 1.0).toFloat(),
        shadowRemove = o.optBoolean("shadowRemove", true),
        binarize = o.optBoolean("binarize", false),
        handwriting = o.optBoolean("handwriting", false)
    )
}
