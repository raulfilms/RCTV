package com.nuvio.tv.core.sync

import com.nuvio.tv.core.rctvcontrol.RctvAccessManager
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.result.PostgrestResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** Reads RCTV's answers like Nuvio's: unknown fields ignored, missing ones at their defaults. */
val SyncRpcJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

/**
 * An answer to a library, watched or progress sync call: from Nuvio's cloud ([postgrestResult]) or from
 * RCTV ([text], its JSON). Decoded the same way either way.
 */
class SyncRpcResult(val postgrestResult: PostgrestResult?, val text: String?) {
    inline fun <reified T : Any> decodeList(): List<T> =
        postgrestResult?.decodeList<T>()
            ?: SyncRpcJson.decodeFromString(text?.takeUnless { it.isBlank() || it == "null" } ?: "[]")

    inline fun <reified T : Any> decodeAs(): T =
        postgrestResult?.decodeAs<T>()
            ?: SyncRpcJson.decodeFromString(text?.takeUnless { it.isBlank() || it == "null" } ?: error("Empty answer"))
}

/**
 * RCTV Control: where a profile's library, watched titles and progress sync. A TV activated with an
 * RCTV account keeps them on the website (every TV of the account shares them: RCTV replaces Nuvio's
 * cloud); any other TV, in Nuvio's cloud as before. The calls and their answers are the same.
 */
@Singleton
class SyncRpc @Inject constructor(
    private val postgrest: Postgrest,
    private val rctvAccess: RctvAccessManager
) {
    /** True while the watch data goes to the RCTV account. */
    val usesRctv: Boolean get() = rctvAccess.watchSyncActive

    suspend fun rpc(name: String, params: JsonObject): SyncRpcResult =
        if (rctvAccess.watchSyncActive) {
            SyncRpcResult(null, rctvAccess.watchSync(name, params.toString()))
        } else {
            SyncRpcResult(postgrest.rpc(name, params), null)
        }
}
