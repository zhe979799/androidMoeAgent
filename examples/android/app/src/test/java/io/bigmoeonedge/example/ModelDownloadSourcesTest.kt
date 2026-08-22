package io.bigmoeonedge.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection

class ModelDownloadSourcesTest {
    @Test
    fun constructsOrderedOfficialAndMainlandCandidates() {
        val sources = ModelCatalog.candidates("https://huggingface.co/acme/repo/resolve/main/model.gguf?download=true")
        assertEquals(listOf("Official", "Mainland mirror"), sources.map { it.label })
        assertEquals("https://hf-mirror.com/acme/repo/resolve/main/model.gguf?download=true", sources[1].url)
        assertEquals(sources, ModelCatalog.selectSources(sources, ModelCatalog.SourceMode.AUTO))
        assertEquals(listOf("Official"), ModelCatalog.selectSources(sources, ModelCatalog.SourceMode.OFFICIAL).map { it.label })
        assertEquals(listOf("Mainland mirror"), ModelCatalog.selectSources(sources, ModelCatalog.SourceMode.MAINLAND_MIRROR).map { it.label })
    }

    @Test
    fun duplicateOrNonHuggingFaceSourcesRemainReplaceableAndOrdered() {
        val sources = ModelCatalog.candidates("https://example.com/model.gguf")
        assertEquals(1, sources.size)
        assertTrue(sources.single().url.startsWith("https://example.com/"))
    }

    @Test
    fun resumeKeepsPartialBytesOnlyWhenRangeWasHonored() {
        assertEquals(123L, DownloadWorker.resumeOffset(123L, HttpURLConnection.HTTP_PARTIAL))
        assertEquals(0L, DownloadWorker.resumeOffset(123L, HttpURLConnection.HTTP_OK))
        assertEquals(123L, DownloadWorker.resumeOffset(123L, 416))
        assertThrows(java.io.IOException::class.java) { DownloadWorker.resumeOffset(123L, 500) }
    }

    @Test
    fun sourceFilenameCannotChangeAndUnsafeNamesAreRejected() {
        assertEquals("model.gguf", DownloadWorker.safeFileName("model.gguf"))
        assertEquals("model.gguf", DownloadWorker.safeFileName("model.gguf?download=true"))
        assertTrue(DownloadWorker.safeFileName("../model.gguf") != "../model.gguf")
    }

    @Test
    fun signedCdnFilenameComesFromContentDispositionNotItsHashPath() {
        assertEquals(
            "model.gguf",
            DownloadWorker.contentDispositionFileName(
                "attachment; filename*=UTF-8''model.gguf; filename=\"other.gguf\"",
            ),
        )
        assertEquals("model.gguf", DownloadWorker.contentDispositionFileName("attachment; filename=\"model.gguf\""))
        assertEquals(null, DownloadWorker.contentDispositionFileName(null))
    }
}
