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
    fun moduleSettingsPermissionDeclarationIsLabGated() {
        val permission = "dev.carepad.permission.MODULE_SETTINGS"
        val permissionDeclaration = "<permission"
        val mainManifest = readManifest("main")
        val debugManifest = readManifest("debug")
        val appGradle = readModuleFile("app/build.gradle.kts")
        val coreManifest = readModuleManifest("core/android/src/main/AndroidManifest.xml")
        val moduleLabManifest = readModuleManifest("module-lab/src/main/AndroidManifest.xml")

        // The normal CarePad manifest may consume the legacy contract, but must not own it.
        assertTrue(mainManifest.contains("<uses-permission"))
        assertTrue(mainManifest.contains("android:name=\"$permission\""))
        assertFalse(mainManifest.contains(permissionDeclaration) && mainManifest.contains(permission))

        // Debug contains the declaration only as a manifest-merger node controlled by the LAB flag.
        assertTrue(debugManifest.contains("android:name=\"$permission\""))
        assertTrue(debugManifest.contains("android:protectionLevel=\"signature\""))
        assertTrue(
            debugManifest.contains(
                "tools:node=\"\${moduleSettingsPermissionDeclarationNode}\""
            )
        )
        assertTrue(
            appGradle.contains(
                "manifestPlaceholders[\"moduleSettingsPermissionDeclarationNode\"] = \"remove\""
            )
        )
        assertTrue(
            appGradle.contains(
                "manifestPlaceholders[\"moduleSettingsPermissionDeclarationNode\"] = \"merge\""
            )
        )

        // Core and Module Lab consume/check the contract but never become permission owners.
        assertFalse(coreManifest.contains(permissionDeclaration) && coreManifest.contains(permission))
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

    private fun readModuleManifest(relativePath: String): String = readModuleFile(relativePath)

    private fun readModuleFile(relativePath: String): String {
        val candidates = listOf(
            File(relativePath),
            File("../$relativePath"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("File not found for path $relativePath")
    }
}
