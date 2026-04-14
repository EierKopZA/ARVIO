package com.arflix.tv.data.repository

import com.arflix.tv.data.model.Addon
import com.arflix.tv.data.model.StreamSource
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

interface CloudstreamArvioProviderEntrypoint {
    fun resolveMovie(request: CloudstreamMovieRequest): List<CloudstreamResolvedStream>
    fun resolveEpisode(request: CloudstreamEpisodeRequest): List<CloudstreamResolvedStream>
}

class ReflectiveCloudstreamArtifactExecutor : CloudstreamArtifactExecutor {
    override suspend fun resolveMovieStreams(
        artifactPath: String,
        request: CloudstreamMovieRequest
    ): List<CloudstreamResolvedStream> {
        val entrypoints = loadEntrypoints(artifactPath)
        return entrypoints.flatMap { it.resolveMovie(request) }
    }

    override suspend fun resolveEpisodeStreams(
        artifactPath: String,
        request: CloudstreamEpisodeRequest
    ): List<CloudstreamResolvedStream> {
        val entrypoints = loadEntrypoints(artifactPath)
        return entrypoints.flatMap { it.resolveEpisode(request) }
    }

    private fun loadEntrypoints(artifactPath: String): List<CloudstreamArvioProviderEntrypoint> {
        val artifactFile = File(artifactPath)
        if (!artifactFile.exists() || !artifactFile.isFile) return emptyList()

        val loader = createClassLoader(artifactFile)
        val providers = java.util.ServiceLoader
            .load(CloudstreamArvioProviderEntrypoint::class.java, loader)
            .toList()
        return providers
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
