package io.bigmoeonedge.example

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticBundleTest {
    @Test
    fun bundleContainsSanitizedManifestAndOnlySelectedDiagnostics() {
        val root = Files.createTempDirectory("bmoe-bundle").toFile()
        val logs = File(root, "logs").apply { mkdirs() }
        val inference = File(root, "inference").apply { mkdirs() }
        val metrics = File(root, "metrics").apply { mkdirs() }
        File(logs, "agent-1.jsonl").writeText("{\"event\":\"finish\",\"answer\":\"see /data/user/0/private.log and C:\\\\secret.txt\"}\n")
        File(inference, "inference-1.jsonl").writeText("{\"event\":\"finish\",\"prompt\":\"why?\",\"answer\":\"because\"}\n")
        File(metrics, "run.csv").writeText("# model=/private/device/path/model.gguf arch=test\nstep,tok_s\n1,2\n")
        val zip = DiagnosticBundle.create(
            File(root, "out"),
            DiagnosticBundle.Inputs(logs.listFiles()!!.toList(), inference.listFiles()!!.toList(), metrics.listFiles()!!.toList()),
            "0.21.2", 38, "abc123", 1234L,
        )
        ZipFile(zip).use { archive ->
            val names = archive.entries().asSequence().map { it.name }.toSet()
            assertTrue(names.contains("manifest.json"))
            assertTrue(names.any { it.startsWith("agent-logs/") })
            assertTrue(names.any { it.startsWith("inference-logs/") })
            assertTrue(names.any { it.startsWith("metrics/") })
            assertFalse(names.any { it.contains("models") })
            val manifest = JSONObject(archive.getInputStream(archive.getEntry("manifest.json")).reader().readText())
            assertTrue(manifest.getString("git_sha") == "abc123")
            assertFalse(manifest.toString().contains(root.absolutePath))
            val exportedLog = archive.entries().asSequence().first { it.name.startsWith("agent-logs/") }
            val logText = archive.getInputStream(exportedLog).reader().readText()
            assertFalse(logText.contains("/data/user/0/private.log"))
            assertFalse(logText.contains("C:\\\\secret.txt"))
            assertTrue(logText.contains("[local path omitted]"))
            val exportedCsv = archive.entries().asSequence().first { it.name.startsWith("metrics/") }
            val csvText = archive.getInputStream(exportedCsv).reader().readText()
            assertTrue(csvText.contains("# model=model.gguf"))
            assertFalse(csvText.contains("/private/device/path"))
        }
    }

    @Test
    fun bundleRetainsOnlyTheNewestThreeArchives() {
        val root = Files.createTempDirectory("bmoe-bundle-retention").toFile()
        val output = File(root, "out")
        try {
            repeat(4) { index ->
                DiagnosticBundle.create(
                    output,
                    DiagnosticBundle.Inputs(emptyList(), emptyList(), emptyList()),
                    "0.21.2", 38, "sha", 1_000L + index,
                )
            }
            val bundles = output.listFiles { file -> file.name.endsWith(".zip") }!!.sortedBy { it.name }
            assertTrue(bundles.size == 3)
            assertFalse(bundles.any { it.name.contains("1000-") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun bundleRejectsOversizedInput() {
        val root = Files.createTempDirectory("bmoe-bundle-large").toFile()
        val log = File(root, "large.jsonl").apply { writeBytes(ByteArray(13 * 1024 * 1024)) }
        try {
            DiagnosticBundle.create(root, DiagnosticBundle.Inputs(listOf(log), emptyList(), emptyList()), "0.21.2", 38, "sha", 2L)
            throw AssertionError("expected size rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("too large"))
        }
    }
}
