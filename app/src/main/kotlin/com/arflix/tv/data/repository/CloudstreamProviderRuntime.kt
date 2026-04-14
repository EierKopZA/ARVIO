package com.arflix.tv.data.repository

import android.content.Context
import android.util.Log
import com.arflix.tv.data.model.Addon
import com.arflix.tv.data.model.StreamSource
import com.lagradost.cloudstream3.MainAPI
import dagger.hilt.android.qualifiers.ApplicationContext
import dalvik.system.DexClassLoader
import java.io.File
import java.net.URLClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val CLOUDSTREAM_TAG = "CloudstreamRuntime"
private const val CLOUDSTREAM_DEBUG_TAG = "CloudStreamDebug"

@Singleton
class CloudstreamProviderRuntime @Inject constructor(
    private val artifactExecutor: CloudstreamArtifactExecutor
) {
    suspend fun resolveMovieStreams(
        addons: List<Addon>,
        title: String,
        year: Int?
    ): List<StreamSource> = withContext(Dispatchers.IO) {
        val primary = resolveWithPerProviderSafety(addons) { addon, artifactPath ->
            artifactExecutor.resolveMovieStreams(
                artifactPath = artifactPath,
                request = CloudstreamMovieRequest(title = title, year = year)
            ).map { it.toStreamSource(addon) }
        }

        if (primary.isNotEmpty() || year == null) {
            return@withContext primary
        }

        Log.w(
            CLOUDSTREAM_TAG,
            "[Cloudstream] empty movie result for title=$title year=$year; retrying without year"
        )

        val fallback = resolveWithPerProviderSafety(addons) { addon, artifactPath ->
            artifactExecutor.resolveMovieStreams(
                artifactPath = artifactPath,
                request = CloudstreamMovieRequest(title = title, year = null)
            ).map { it.toStreamSource(addon) }
        }

        Log.d(
            CLOUDSTREAM_TAG,
            "[Cloudstream] movie fallback result title=$title fallbackStreams=${fallback.size}"
        )
        fallback
    }

    suspend fun resolveEpisodeStreams(
        addons: List<Addon>,
        title: String,
        year: Int?,
        season: Int,
        episode: Int,
        airDate: String?
    ): List<StreamSource> = withContext(Dispatchers.IO) {
        resolveWithPerProviderSafety(addons) { addon, artifactPath ->
            artifactExecutor.resolveEpisodeStreams(
                artifactPath = artifactPath,
                request = CloudstreamEpisodeRequest(
                    title = title,
                    year = year,
                    season = season,
                    episode = episode,
                    airDate = airDate
                )
            ).map { it.toStreamSource(addon) }
        }
    }

    private suspend fun resolveWithPerProviderSafety(
        addons: List<Addon>,
        resolver: suspend (addon: Addon, artifactPath: String) -> List<StreamSource>
    ): List<StreamSource> = coroutineScope {
        addons
            .mapNotNull { addon ->
                addon.installedArtifactPath
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { path -> addon to path }
            }
            .map { (addon, artifactPath) ->
                async {
                    runCatching { resolver(addon, artifactPath) }
                        .getOrElse { error ->
                            Log.e(
                                CLOUDSTREAM_DEBUG_TAG,
                                "Failed to load/instantiate provider",
                                error
                            )
                            emptyList()
                        }
                }
            }
            .awaitAll()
            .flatten()
    }
}

data class CloudstreamMovieRequest(
    val title: String,
    val year: Int?
)

data class CloudstreamEpisodeRequest(
    val title: String,
    val year: Int?,
    val season: Int,
    val episode: Int,
    val airDate: String?
)

data class CloudstreamResolvedStream(
    val name: String,
    val quality: String = "",
    val size: String = "",
    val sizeBytes: Long? = null,
    val url: String?,
    val infoHash: String? = null,
    val fileIndex: Int? = null,
    val subtitles: List<com.arflix.tv.data.model.Subtitle> = emptyList(),
    val sourceUrls: List<String> = emptyList()
)

interface CloudstreamArtifactExecutor {
    suspend fun resolveMovieStreams(
        artifactPath: String,
        request: CloudstreamMovieRequest
    ): List<CloudstreamResolvedStream>

    suspend fun resolveEpisodeStreams(
        artifactPath: String,
        request: CloudstreamEpisodeRequest
    ): List<CloudstreamResolvedStream>
}

interface CloudstreamArvioProviderEntrypoint : MainAPI {
    fun resolveMovie(request: CloudstreamMovieRequest): List<CloudstreamResolvedStream>
    fun resolveEpisode(request: CloudstreamEpisodeRequest): List<CloudstreamResolvedStream>
}

class ReflectiveCloudstreamArtifactExecutor @Inject constructor(
    @ApplicationContext private val context: Context
) : CloudstreamArtifactExecutor {
    override suspend fun resolveMovieStreams(
        artifactPath: String,
        request: CloudstreamMovieRequest
    ): List<CloudstreamResolvedStream> {
        val entrypoints = loadEntrypoints(artifactPath)
        if (entrypoints.isEmpty()) {
            Log.w(CLOUDSTREAM_TAG, "[Cloudstream] no entrypoints loaded artifact=$artifactPath")
            return emptyList()
        }

        return entrypoints.flatMap { entrypoint ->
            val providerLabel = entrypoint.javaClass.simpleName.ifBlank { "UnknownProvider" }
            Log.d(
                CLOUDSTREAM_DEBUG_TAG,
                "Executing search for: ${request.title} on $providerLabel"
            )
            val raw = runCatching { entrypoint.resolveMovie(request) }
                .onFailure {
                    Log.e(CLOUDSTREAM_DEBUG_TAG, "Scraping failed", it)
                    Log.w(
                        CLOUDSTREAM_TAG,
                        "[Cloudstream] movie resolve failed artifact=$artifactPath provider=$providerLabel error=${it.message}"
                    )
                }
                .getOrDefault(emptyList())
            Log.d(CLOUDSTREAM_DEBUG_TAG, "Search returned ${raw.size} items")

            val fuzzyWinner = pickFuzzyWinner(request.title, raw.map { it.name })
            if (fuzzyWinner != null) {
                Log.d(
                    CLOUDSTREAM_DEBUG_TAG,
                    "Fuzzy match winner: ${fuzzyWinner.first} with score ${"%.2f".format(fuzzyWinner.second)}"
                )
            }

            val filtered = when {
                fuzzyWinner != null -> raw.filter { stream -> stream.name == fuzzyWinner.first }
                else -> raw.filter { stream -> shouldKeepByTitle(request.title, stream.name) }
            }
            if (raw.isNotEmpty() && filtered.isEmpty()) {
                Log.w(
                    CLOUDSTREAM_TAG,
                    "[Cloudstream] title filter dropped all movie streams; using raw results artifact=$artifactPath provider=$providerLabel title=${request.title} raw=${raw.size}"
                )
                raw
            } else {
                Log.d(
                    CLOUDSTREAM_TAG,
                    "[Cloudstream] movie streams artifact=$artifactPath provider=$providerLabel raw=${raw.size} filtered=${filtered.size}"
                )
                filtered
            }
        }
    }

    override suspend fun resolveEpisodeStreams(
        artifactPath: String,
        request: CloudstreamEpisodeRequest
    ): List<CloudstreamResolvedStream> {
        val entrypoints = loadEntrypoints(artifactPath)
        if (entrypoints.isEmpty()) {
            Log.w(CLOUDSTREAM_TAG, "[Cloudstream] no entrypoints loaded artifact=$artifactPath")
            return emptyList()
        }

        return entrypoints.flatMap { entrypoint ->
            val providerLabel = entrypoint.javaClass.simpleName.ifBlank { "UnknownProvider" }
            Log.d(
                CLOUDSTREAM_DEBUG_TAG,
                "Executing search for: ${request.title} on $providerLabel"
            )
            val raw = runCatching { entrypoint.resolveEpisode(request) }
                .onFailure {
                    Log.e(CLOUDSTREAM_DEBUG_TAG, "Scraping failed", it)
                    Log.w(
                        CLOUDSTREAM_TAG,
                        "[Cloudstream] episode resolve failed artifact=$artifactPath provider=$providerLabel error=${it.message}"
                    )
                }
                .getOrDefault(emptyList())
            Log.d(CLOUDSTREAM_DEBUG_TAG, "Search returned ${raw.size} items")

            val fuzzyWinner = pickFuzzyWinner(request.title, raw.map { it.name })
            if (fuzzyWinner != null) {
                Log.d(
                    CLOUDSTREAM_DEBUG_TAG,
                    "Fuzzy match winner: ${fuzzyWinner.first} with score ${"%.2f".format(fuzzyWinner.second)}"
                )
            }

            val filtered = when {
                fuzzyWinner != null -> raw.filter { stream -> stream.name == fuzzyWinner.first }
                else -> raw.filter { stream -> shouldKeepByTitle(request.title, stream.name) }
            }
            if (raw.isNotEmpty() && filtered.isEmpty()) {
                Log.w(
                    CLOUDSTREAM_TAG,
                    "[Cloudstream] title filter dropped all episode streams; using raw results artifact=$artifactPath provider=$providerLabel title=${request.title} raw=${raw.size}"
                )
                raw
            } else {
                Log.d(
                    CLOUDSTREAM_TAG,
                    "[Cloudstream] episode streams artifact=$artifactPath provider=$providerLabel raw=${raw.size} filtered=${filtered.size}"
                )
                filtered
            }
        }
    }

    private fun loadEntrypoints(artifactPath: String): List<CloudstreamArvioProviderEntrypoint> {
        return try {
            val artifactFile = File(artifactPath)
            if (!artifactFile.exists() || !artifactFile.isFile) return emptyList()
            val privateRoot = File(context.filesDir, "cloudstream_plugins")
            if (!isPathInside(privateRoot, artifactFile)) return emptyList()

            val loader = createClassLoader(artifactFile)
            val providers = java.util.ServiceLoader
                .load(CloudstreamArvioProviderEntrypoint::class.java, loader)
                .toList()
            providers
        } catch (e: ClassNotFoundException) {
            Log.e(CLOUDSTREAM_DEBUG_TAG, "Failed to load/instantiate provider", e)
            emptyList()
        } catch (e: IllegalAccessException) {
            Log.e(CLOUDSTREAM_DEBUG_TAG, "Failed to load/instantiate provider", e)
            emptyList()
        } catch (e: InstantiationException) {
            Log.e(CLOUDSTREAM_DEBUG_TAG, "Failed to load/instantiate provider", e)
            emptyList()
        } catch (e: java.util.ServiceConfigurationError) {
            Log.e(CLOUDSTREAM_DEBUG_TAG, "Failed to load/instantiate provider", e)
            emptyList()
        }
    }

    private fun createClassLoader(artifactFile: File): ClassLoader {
        return try {
            val optimizedDir = File(
                artifactFile.parentFile ?: artifactFile.absoluteFile.parentFile,
                "optimized_dex"
            ).apply { mkdirs() }
            DexClassLoader(
                artifactFile.absolutePath,
                optimizedDir.absolutePath,
                null,
                CloudstreamArvioProviderEntrypoint::class.java.classLoader
            )
        } catch (_: Throwable) {
            URLClassLoader(
                arrayOf(artifactFile.toURI().toURL()),
                CloudstreamArvioProviderEntrypoint::class.java.classLoader
            )
        }
    }
}

private fun shouldKeepByTitle(expectedTitle: String, candidateTitle: String): Boolean {
    if (expectedTitle.isBlank() || candidateTitle.isBlank()) return true
    return similarity(expectedTitle, candidateTitle) >= 0.80
}

private fun pickFuzzyWinner(expectedTitle: String, candidateTitles: List<String>): Pair<String, Double>? {
    if (expectedTitle.isBlank()) return null
    return candidateTitles
        .asSequence()
        .filter { it.isNotBlank() }
        .map { candidate -> candidate to similarity(expectedTitle, candidate) }
        .maxByOrNull { it.second }
        ?.takeIf { it.second >= 0.80 }
}

private fun isPathInside(root: File, target: File): Boolean {
    val rootPath = root.canonicalFile.toPath()
    val targetPath = target.canonicalFile.toPath()
    return targetPath.startsWith(rootPath)
}

private fun CloudstreamResolvedStream.toStreamSource(addon: Addon): StreamSource {
    return StreamSource(
        source = name.ifBlank { addon.name },
        addonName = addon.name,
        addonId = addon.id,
        quality = quality,
        size = size,
        sizeBytes = sizeBytes,
        url = url,
        infoHash = infoHash,
        fileIdx = fileIndex,
        subtitles = subtitles,
        sources = sourceUrls
    )
}

private fun levenshtein(a: String, b: String): Int {
    val dp = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        var prev = dp[0]
        dp[0] = i
        for (j in 1..b.length) {
            val temp = dp[j]
            dp[j] = minOf(
                dp[j] + 1,
                dp[j - 1] + 1,
                prev + if (a[i - 1] == b[j - 1]) 0 else 1
            )
            prev = temp
        }
    }
    return dp[b.length]
}

private fun similarity(a: String, b: String): Double {
    val aa = a.lowercase().trim()
    val bb = b.lowercase().trim()
    if (aa.isEmpty() || bb.isEmpty()) return 0.0
    val dist = levenshtein(aa, bb)
    return 1.0 - (dist.toDouble() / maxOf(aa.length, bb.length).toDouble())
}
