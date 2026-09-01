package com.joel.thordoctor.core.settings

import android.content.Context
import android.net.Uri
import carepad.contracts.CarePadSettingResult
import carepad.contracts.CarePadSettingsAuthorities
import carepad.contracts.CarePadSettingsMethods
import carepad.contracts.CarePadSettingsProtocol
import carepad.contracts.CarePadSettingsSnapshotResult

/**
 * Host-side client for invoking inline settings operations on discovered CarePad modules.
 *
 * NOTE ON SYNCHRONY & THREADING:
 * All methods in this client perform synchronous, blocking Android IPC calls via ContentResolver.call().
 * The calling layer (ViewModel, background worker, or higher-level coordinator) is strictly responsible
 * for ensuring these calls are not executed on the Android Main (UI) Thread to prevent UI freezes or ANRs.
 */
object CarePadSettingsClient {

    /**
     * Synchronously requests a settings snapshot from the target module.
     *
     * @param context Application or activity context.
     * @param modulePackageName Package name of the previously discovered and signature-verified module.
     */
    fun getSnapshot(
        context: Context,
        modulePackageName: String
    ): CarePadSettingsSnapshotResult {
        val authority = CarePadSettingsAuthorities.forPackage(modulePackageName)

        // Fail-closed verification: ensure the provider belongs strictly to the accepted package
        if (!CarePadSettingsSecurity.isProviderOwnedByPackage(context, authority, modulePackageName)) {
            return CarePadSettingsSnapshotResult.Unavailable(
                "Settings provider for $modulePackageName was not found or has mismatched package ownership."
            )
        }

        val uri = Uri.parse("content://$authority")
        val reqBundle = CarePadSettingsBundleCodec.encodeWriteBooleanRequest(
            contractVersion = CarePadSettingsProtocol.CONTRACT_VERSION,
            catalogRevision = "",
            itemId = "",
            value = false
        ) // Re-using basic bundle with contract version

        return try {
            val response = context.contentResolver.call(
                uri,
                CarePadSettingsMethods.GET_SNAPSHOT,
                null,
                reqBundle
            )
            CarePadSettingsBundleCodec.decodeSnapshotResult(response)
        } catch (e: Throwable) {
            CarePadSettingsSnapshotResult.Unavailable("Settings IPC error: ${e.message ?: e::class.java.simpleName}")
        }
    }

    /**
     * Synchronously sends a boolean setting update to the target module.
     *
     * @param context Application or activity context.
     * @param modulePackageName Package name of the module.
     * @param catalogRevision The snapshot revision upon which this write was based.
     * @param itemId Opaque identifier of the boolean setting.
     * @param value Desired boolean value.
     */
    fun writeBoolean(
        context: Context,
        modulePackageName: String,
        catalogRevision: String,
        itemId: String,
        value: Boolean
    ): CarePadSettingResult {
        val authority = CarePadSettingsAuthorities.forPackage(modulePackageName)

        if (!CarePadSettingsSecurity.isProviderOwnedByPackage(context, authority, modulePackageName)) {
            return CarePadSettingResult.Unavailable(
                "Settings provider for $modulePackageName was not found or has mismatched package ownership."
            )
        }

        val uri = Uri.parse("content://$authority")
        val reqBundle = CarePadSettingsBundleCodec.encodeWriteBooleanRequest(
            contractVersion = CarePadSettingsProtocol.CONTRACT_VERSION,
            catalogRevision = catalogRevision,
            itemId = itemId,
            value = value
        )

        return try {
            val response = context.contentResolver.call(
                uri,
                CarePadSettingsMethods.WRITE_BOOLEAN,
                null,
                reqBundle
            )
            CarePadSettingsBundleCodec.decodeSettingResult(response)
        } catch (e: Throwable) {
            CarePadSettingResult.Unavailable("Settings IPC error: ${e.message ?: e::class.java.simpleName}")
        }
    }

    /**
     * Synchronously sends a single-choice setting update to the target module.
     *
     * @param context Application or activity context.
     * @param modulePackageName Package name of the module.
     * @param catalogRevision The snapshot revision upon which this write was based.
     * @param itemId Opaque identifier of the single-choice setting.
     * @param selectedOptionId Chosen option identifier.
     */
    fun writeSingleChoice(
        context: Context,
        modulePackageName: String,
        catalogRevision: String,
        itemId: String,
        selectedOptionId: String
    ): CarePadSettingResult {
        val authority = CarePadSettingsAuthorities.forPackage(modulePackageName)

        if (!CarePadSettingsSecurity.isProviderOwnedByPackage(context, authority, modulePackageName)) {
            return CarePadSettingResult.Unavailable(
                "Settings provider for $modulePackageName was not found or has mismatched package ownership."
            )
        }

        val uri = Uri.parse("content://$authority")
        val reqBundle = CarePadSettingsBundleCodec.encodeWriteSingleChoiceRequest(
            contractVersion = CarePadSettingsProtocol.CONTRACT_VERSION,
            catalogRevision = catalogRevision,
            itemId = itemId,
            selectedOptionId = selectedOptionId
        )

        return try {
            val response = context.contentResolver.call(
                uri,
                CarePadSettingsMethods.WRITE_SINGLE_CHOICE,
                null,
                reqBundle
            )
            CarePadSettingsBundleCodec.decodeSettingResult(response)
        } catch (e: Throwable) {
            CarePadSettingResult.Unavailable("Settings IPC error: ${e.message ?: e::class.java.simpleName}")
        }
    }
}
