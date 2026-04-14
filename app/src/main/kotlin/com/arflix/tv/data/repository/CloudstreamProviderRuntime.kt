package com.arflix.tv.data.repository

import android.content.Context
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

@Singleton
class CloudstreamProviderRuntime @Inject constructor(
    private val artifactExecutor: CloudstreamArtifactExecutor
) {
    suspend fun resolveMovieStreams(
        addons: List<Addon>,
        title: String,
        year: Int?
    ): List<StreamSource> = withContext(Dispatchers.IO) {
        resolveWithPerProviderSafety(addons) { addon, artifactPath ->
            artifactExecutor.resolveMovieStreams(
                artifactPath = artifactPath,
                request = CloudstreamMovieRequest(title = title, year = year)
            ).map { it.toStreamSource(addon) }
        }
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
                        .getOrElse { emptyList() }
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
        return entrypoints.flatMap { entrypoint ->
            entrypoint.resolveMovie(request)
                .filter { stream -> shouldKeepByTitle(request.title, stream.name) }
        }
    }

    override suspend fun resolveEpisodeStreams(
        artifactPath: String,
        request: CloudstreamEpisodeRequest
    ): List<CloudstreamResolvedStream> {
        val entrypoints = loadEntrypoints(artifactPath)
        return entrypoints.flatMap { entrypoint ->
            entrypoint.resolveEpisode(request)
                .filter { stream -> shouldKeepByTitle(request.title, stream.name) }
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
            emptyList()
        } catch (e: IllegalAccessException) {
            emptyList()
        } catch (e: InstantiationException) {
            emptyList()
        } catch (e: java.util.ServiceConfigurationError) {
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
    return similarity(expectedTitle, candidateTitle) >= 0.82
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
