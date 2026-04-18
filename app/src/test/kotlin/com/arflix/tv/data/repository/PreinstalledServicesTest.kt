package com.arflix.tv.data.repository

import com.arflix.tv.data.model.CatalogConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises `buildPreinstalledDefaults()` in MediaRepository. That's the
 * entry point used by getDefaultCatalogConfigs() to seed a fresh profile's
 * catalogs.
 */
class PreinstalledServicesTest {

    private val premiumSevenOrder = listOf(
        "collection_service_netflix",
        "collection_service_prime",
        "collection_service_appletv",
        "collection_service_disney",
        "collection_service_hbo",
        "collection_service_hulu",
        "collection_service_paramount"
    )

    private val extraFive = setOf(
        "collection_service_shudder",
        "collection_service_jiohotstar",
        "collection_service_sonyliv",
        "collection_service_sky",
        "collection_service_crunchyroll"
    )

    private fun loadServices() =
        MediaRepository.buildPreinstalledDefaults()
            .filter { it.id.startsWith("collection_service_") }

    @Test
    fun `premium 7 services appear in order at the top of the list`() {
        val services = loadServices()
        val firstSevenIds = services.take(7).map { it.id }
        assertEquals(premiumSevenOrder, firstSevenIds)
    }

    @Test
    fun `all 12 services have focusGif equal to cover (no distinct GIF)`() {
        // The helper defaults `collectionFocusGifUrl` to `focusGif ?: cover`,
        // so passing focusGif = null resolves to the cover PNG itself. The
        // home-row tile treats `backdrop == image` as "no focus swap".
        val services = loadServices()
        assertEquals(12, services.size)
        services.forEach { cfg ->
            assertEquals(
                "Service ${cfg.id} focusGif must equal cover (no distinct GIF)",
                cfg.collectionCoverImageUrl,
                cfg.collectionFocusGifUrl
            )
        }
    }

    @Test
    fun `all 12 services have null collectionClearLogoUrl`() {
        val services = loadServices()
        services.forEach { cfg ->
            assertNull(
                "Service ${cfg.id} should not have a clearLogo",
                cfg.collectionClearLogoUrl
            )
        }
    }

    @Test
    fun `premium 7 services have mrtxiv cover and heroVideo URLs`() {
        val services = loadServices().filter { it.id in premiumSevenOrder }
        val expectedCovers = mapOf(
            "collection_service_netflix" to "networks%20collection/netflix.png",
            "collection_service_prime" to "networks%20collection/amazonprime.png",
            "collection_service_appletv" to "networks%20collection/appletvplus.png",
            "collection_service_disney" to "networks%20collection/disneyplus.png",
            "collection_service_hbo" to "networks%20collection/hbomax.png",
            "collection_service_hulu" to "networks%20collection/hulu.png",
            "collection_service_paramount" to "networks%20collection/paramount.png"
        )
        val expectedVideos = mapOf(
            "collection_service_netflix" to "networks%20videos/netflix.mp4",
            "collection_service_prime" to "networks%20videos/amazonprime.mp4",
            "collection_service_appletv" to "networks%20videos/appletv.mp4",
            "collection_service_disney" to "networks%20videos/disneyplus.mp4",
            "collection_service_hbo" to "networks%20videos/hbomax.mp4",
            "collection_service_hulu" to "networks%20videos/hulu.mp4",
            "collection_service_paramount" to "networks%20videos/paramount.mp4"
        )
        services.forEach { cfg ->
            val cover = cfg.collectionCoverImageUrl
            val video = cfg.collectionHeroVideoUrl
            assertNotNull("${cfg.id} cover", cover)
            assertNotNull("${cfg.id} heroVideo", video)
            assertTrue(
                "${cfg.id} cover must be mrtxiv asset, was $cover",
                cover!!.contains("raw.githubusercontent.com/mrtxiv/networks-video-collection") &&
                    cover.endsWith(expectedCovers[cfg.id]!!)
            )
            assertTrue(
                "${cfg.id} heroVideo must be mrtxiv asset, was $video",
                video!!.contains("raw.githubusercontent.com/mrtxiv/networks-video-collection") &&
                    video.endsWith(expectedVideos[cfg.id]!!)
            )
        }
    }

    @Test
    fun `5 extra services have no heroVideo`() {
        val services = loadServices().filter { it.id in extraFive }
        assertEquals(5, services.size)
        services.forEach { cfg ->
            assertNull("${cfg.id} should not have heroVideo", cfg.collectionHeroVideoUrl)
        }
    }
}
