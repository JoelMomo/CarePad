package com.joel.thordoctor.modules.host

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarePadManifestContractTest {
    @Test
    fun requestDeletePackagesIsOwnedByMainManifestForAllVariants() {
        val permission = "android.permission.REQUEST_DELETE_PACKAGES"
        val mainManifest = readManifest("main")
        val debugManifest = readManifest("debug")

        assertTrue(mainManifest.contains(permission))
        assertEquals(1, mainManifest.windowed(permission.length).count { it == permission })
        assertFalse(debugManifest.contains(permission))
    }

    private fun readManifest(sourceSet: String): String {
        val candidates = listOf(
            File("src/$sourceSet/AndroidManifest.xml"),
            File("app/src/$sourceSet/AndroidManifest.xml"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("AndroidManifest.xml not found for source set $sourceSet")
    }
}
