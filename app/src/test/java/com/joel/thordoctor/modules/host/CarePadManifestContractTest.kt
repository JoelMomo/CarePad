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

    @Test
    fun moduleSettingsPermissionIsDefinedOnlyByMainManifest() {
        val permission = "dev.carepad.permission.MODULE_SETTINGS"
        val permissionDeclaration = "<permission"
        val mainManifest = readManifest("main")
        val debugManifest = readManifest("debug")
        val coreManifest = readModuleManifest("core/android/src/main/AndroidManifest.xml")
        val moduleLabManifest = readModuleManifest("module-lab/src/main/AndroidManifest.xml")

        assertTrue(mainManifest.contains(permission))
        assertTrue(mainManifest.contains("android:name=\"$permission\""))
        assertTrue(mainManifest.contains("android:protectionLevel=\"signature\""))

        // Debug and Core must not declare the permission
        assertFalse(debugManifest.contains(permissionDeclaration) && debugManifest.contains(permission))
        assertFalse(coreManifest.contains(permission))

        // Module Lab must use the permission but not declare it
        assertTrue(moduleLabManifest.contains("<uses-permission android:name=\"$permission\""))
        assertFalse(moduleLabManifest.contains(permissionDeclaration) && moduleLabManifest.contains(permission))
    }

    private fun readManifest(sourceSet: String): String {
        val candidates = listOf(
            File("src/$sourceSet/AndroidManifest.xml"),
            File("app/src/$sourceSet/AndroidManifest.xml"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("AndroidManifest.xml not found for source set $sourceSet")
    }

    private fun readModuleManifest(relativePath: String): String {
        val candidates = listOf(
            File(relativePath),
            File("../$relativePath"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Manifest not found for path $relativePath")
    }
}
