package com.joel.thordoctor.core.settings

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import carepad.contracts.CarePadSettingResult
import carepad.contracts.CarePadSettingsMethods
import carepad.contracts.CarePadSettingsProtocol
import carepad.contracts.CarePadSettingsSnapshotResult

/** Base ContentProvider for the narrow C0 inline-settings RPC. */
abstract class CarePadSettingsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val currentContext = context ?: return unavailableFor(method)
        if (!CarePadSettingsSecurity.isCallingPackageTrusted(currentContext)) {
            return unavailableFor(method)
        }

        return try {
            when (method) {
                CarePadSettingsMethods.GET_SNAPSHOT -> handleGetSnapshot(extras)
                CarePadSettingsMethods.WRITE_BOOLEAN -> handleWriteBoolean(extras)
                CarePadSettingsMethods.WRITE_SINGLE_CHOICE -> handleWriteSingleChoice(extras)
                else -> unavailableFor(method)
            }
        } catch (_: Exception) {
            unavailableFor(method)
        }
    }

    private fun handleGetSnapshot(extras: Bundle?): Bundle {
        val requestVersion = CarePadSettingsBundleCodec.decodeGetSnapshotContractVersion(extras)
            ?: return CarePadSettingsBundleCodec.encodeSnapshotUnavailable(null)
        if (requestVersion != CarePadSettingsProtocol.CONTRACT_VERSION) {
            return CarePadSettingsBundleCodec.encodeSnapshotIncompatible(
                supportedContractVersion = CarePadSettingsProtocol.CONTRACT_VERSION
            )
        }

        return when (val result = onGetSnapshot()) {
            is CarePadSettingsSnapshotResult.Success ->
                CarePadSettingsBundleCodec.encodeSnapshotSuccess(result.snapshot)
            is CarePadSettingsSnapshotResult.Unavailable ->
                CarePadSettingsBundleCodec.encodeSnapshotUnavailable(result.message)
            is CarePadSettingsSnapshotResult.Incompatible ->
                CarePadSettingsBundleCodec.encodeSnapshotIncompatible(
                    result.supportedContractVersion,
                    result.message
                )
        }
    }

    private fun handleWriteBoolean(extras: Bundle?): Bundle {
        val request = CarePadSettingsBundleCodec.decodeWriteBooleanRequest(extras)
            ?: return CarePadSettingsBundleCodec.encodeSettingResult(CarePadSettingResult.Unavailable())
        if (request.contractVersion != CarePadSettingsProtocol.CONTRACT_VERSION) {
            return CarePadSettingsBundleCodec.encodeSettingResult(
                CarePadSettingResult.Incompatible(CarePadSettingsProtocol.CONTRACT_VERSION)
            )
        }
        return CarePadSettingsBundleCodec.encodeSettingResult(
            onWriteBoolean(request.catalogRevision, request.itemId, request.value)
        )
    }

    private fun handleWriteSingleChoice(extras: Bundle?): Bundle {
        val request = CarePadSettingsBundleCodec.decodeWriteSingleChoiceRequest(extras)
            ?: return CarePadSettingsBundleCodec.encodeSettingResult(CarePadSettingResult.Unavailable())
        if (request.contractVersion != CarePadSettingsProtocol.CONTRACT_VERSION) {
            return CarePadSettingsBundleCodec.encodeSettingResult(
                CarePadSettingResult.Incompatible(CarePadSettingsProtocol.CONTRACT_VERSION)
            )
        }
        return CarePadSettingsBundleCodec.encodeSettingResult(
            onWriteSingleChoice(request.catalogRevision, request.itemId, request.selectedOptionId)
        )
    }

    private fun unavailableFor(method: String): Bundle =
        if (method == CarePadSettingsMethods.GET_SNAPSHOT) {
            CarePadSettingsBundleCodec.encodeSnapshotUnavailable(null)
        } else {
            CarePadSettingsBundleCodec.encodeSettingResult(CarePadSettingResult.Unavailable())
        }

    abstract fun onGetSnapshot(): CarePadSettingsSnapshotResult

    abstract fun onWriteBoolean(
        catalogRevision: String,
        itemId: String,
        value: Boolean
    ): CarePadSettingResult

    abstract fun onWriteSingleChoice(
        catalogRevision: String,
        itemId: String,
        selectedOptionId: String
    ): CarePadSettingResult

    final override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    final override fun getType(uri: Uri): String? = null
    final override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    final override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    final override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
