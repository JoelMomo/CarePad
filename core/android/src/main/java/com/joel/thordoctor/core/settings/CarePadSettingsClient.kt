package com.joel.thordoctor.core.settings

import android.content.Context
import android.net.Uri
import carepad.contracts.CarePadSettingResult
import carepad.contracts.CarePadSettingsAuthorities
import carepad.contracts.CarePadSettingsMethods
import carepad.contracts.CarePadSettingsProtocol
import carepad.contracts.CarePadSettingsSnapshotResult

/** Host-side synchronous IPC client. Callers must keep these calls off the UI thread. */
object CarePadSettingsClient {
    private const val GENERIC_UNAVAILABLE_MESSAGE = "Module settings are unavailable."

    fun getSnapshot(
        context: Context,
        modulePackageName: String
    ): CarePadSettingsSnapshotResult {
        val authority = CarePadSettingsAuthorities.forPackage(modulePackageName)
        if (!CarePadSettingsSecurity.isProviderOwnedByPackage(context, authority, modulePackageName)) {
            return unavailableSnapshot()
        }

        val response = try {
            context.contentResolver.call(
                Uri.parse("content://$authority"),
                CarePadSettingsMethods.GET_SNAPSHOT,
                null,
                CarePadSettingsBundleCodec.encodeGetSnapshotRequest(
                    CarePadSettingsProtocol.CONTRACT_VERSION
                )
            )
        } catch (_: Exception) {
            return unavailableSnapshot()
        }
        return CarePadSettingsBundleCodec.decodeSnapshotResult(response)
    }

    fun writeBoolean(
        context: Context,
        modulePackageName: String,
        catalogRevision: String,
        itemId: String,
        value: Boolean
    ): CarePadSettingResult {
        val authority = CarePadSettingsAuthorities.forPackage(modulePackageName)
        if (!CarePadSettingsSecurity.isProviderOwnedByPackage(context, authority, modulePackageName)) {
            return unavailableSetting()
        }

        val response = try {
            context.contentResolver.call(
                Uri.parse("content://$authority"),
                CarePadSettingsMethods.WRITE_BOOLEAN,
                null,
                CarePadSettingsBundleCodec.encodeWriteBooleanRequest(
                    contractVersion = CarePadSettingsProtocol.CONTRACT_VERSION,
                    catalogRevision = catalogRevision,
                    itemId = itemId,
                    value = value
                )
            )
        } catch (_: Exception) {
            return unavailableSetting()
        }
        return CarePadSettingsBundleCodec.decodeBooleanSettingResult(response)
    }

    fun writeSingleChoice(
        context: Context,
        modulePackageName: String,
        catalogRevision: String,
        itemId: String,
        selectedOptionId: String
    ): CarePadSettingResult {
        val authority = CarePadSettingsAuthorities.forPackage(modulePackageName)
        if (!CarePadSettingsSecurity.isProviderOwnedByPackage(context, authority, modulePackageName)) {
            return unavailableSetting()
        }

        val response = try {
            context.contentResolver.call(
                Uri.parse("content://$authority"),
                CarePadSettingsMethods.WRITE_SINGLE_CHOICE,
                null,
                CarePadSettingsBundleCodec.encodeWriteSingleChoiceRequest(
                    contractVersion = CarePadSettingsProtocol.CONTRACT_VERSION,
                    catalogRevision = catalogRevision,
                    itemId = itemId,
                    selectedOptionId = selectedOptionId
                )
            )
        } catch (_: Exception) {
            return unavailableSetting()
        }
        return CarePadSettingsBundleCodec.decodeSingleChoiceSettingResult(response)
    }

    private fun unavailableSnapshot(): CarePadSettingsSnapshotResult =
        CarePadSettingsSnapshotResult.Unavailable(GENERIC_UNAVAILABLE_MESSAGE)

    private fun unavailableSetting(): CarePadSettingResult =
        CarePadSettingResult.Unavailable(GENERIC_UNAVAILABLE_MESSAGE)
}
