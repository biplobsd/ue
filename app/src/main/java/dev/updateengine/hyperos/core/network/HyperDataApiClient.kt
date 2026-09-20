package dev.updateengine.hyperos.core.network

import dev.updateengine.hyperos.core.common.AppDispatchers
import dev.updateengine.hyperos.core.model.OtaTarget
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class HyperDataApiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val dispatchers: AppDispatchers = AppDispatchers()
) {

    private val mirrorEndpoints = listOf(
        "https://data.hyperos.fans/devices",
        "https://cdn.jsdelivr.net/gh/HegeKen/HyperData@main/devices",
        "https://raw.githubusercontent.com/HegeKen/HyperData/main/devices"
    )

    suspend fun getRomsForDevice(codename: String): Result<List<OtaTarget>> = withContext(dispatchers.io) {
        var lastException: Exception? = null

        for (endpoint in mirrorEndpoints) {
            val url = "$endpoint/$codename.json"
            try {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("HTTP ${response.code} from $url")
                    }
                    val body = response.body?.string() ?: throw Exception("Empty response from $url")
                    val targets = parseDeviceJson(codename, body)
                    if (targets.isNotEmpty()) {
                        return@withContext Result.success(targets)
                    }
                }
            } catch (e: Exception) {
                lastException = e
            }
        }

        Result.failure(lastException ?: Exception("Failed to fetch ROMs for device $codename from all mirrors"))
    }

    private fun parseDeviceJson(codename: String, jsonStr: String): List<OtaTarget> {
        val root = json.parseToJsonElement(jsonStr).jsonObject
        val branches = root["branches"]?.jsonArray ?: return emptyList()

        val results = mutableListOf<OtaTarget>()

        for (branchElem in branches) {
            val branchObj = branchElem.jsonObject
            val nameElem = branchObj["name"]
            val branchName = when {
                nameElem is kotlinx.serialization.json.JsonObject -> {
                    nameElem["en"]?.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.content else null }
                        ?: nameElem["zh"]?.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.content else null }
                        ?: "Official"
                }
                nameElem is kotlinx.serialization.json.JsonPrimitive -> nameElem.content
                else -> "Official"
            }
            val romsObj = branchObj["roms"]?.jsonObject ?: continue

            for ((_, romElem) in romsObj) {
                val rom = romElem.jsonObject
                val os = rom["os"]?.jsonPrimitive?.content ?: continue
                val android = rom["android"]?.jsonPrimitive?.content ?: ""
                val release = rom["release"]?.jsonPrimitive?.content ?: ""
                val recovery = rom["recovery"]?.jsonPrimitive?.content ?: ""

                // Guard: Skip entries where recovery is empty or not full zip
                if (recovery.isBlank() || !recovery.endsWith(".zip", ignoreCase = true)) {
                    continue
                }

                val downloadUrls = listOf(
                    "https://bn.d.miui.com/$os/$recovery",
                    "https://bkt-sgp-miui-ota-update-alisgp.oss-ap-southeast-1.aliyuncs.com/$os/$recovery",
                    "https://bigota.d.miui.com/$os/$recovery"
                )

                results.add(
                    OtaTarget(
                        device = codename,
                        branch = branchName,
                        osVersion = os,
                        androidVersion = android,
                        releaseDate = release,
                        recoveryFilename = recovery,
                        downloadUrls = downloadUrls
                    )
                )
            }
        }

        return results
    }
}
