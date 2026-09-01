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

/**
 * Base ContentProvider for CarePad modules exposing inline settings via RPC.
 *
 * Implements defensive caller validation, protocol dispatching, and Bundle encoding/decoding.
 * Standard CRUD operations are explicitly disabled as this provider only operates via call().
 */
abstract class CarePadSettingsProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val currentContext = context ?: return CarePadSettingsBundleCodec.encodeSettingResult(
            CarePadSettingResult.Unavailable("Provider context is null")
        )

        // Enforce defensive caller verification (signature digest must match)
        if (!CarePadSettingsSecurity.isCallingPackageTrusted(currentContext)) {
            return CarePadSettingsBundleCodec.encodeSettingResult(
                CarePadSettingResult.Unavailable("Untrusted caller signature")
            )
        }

        return when (method) {
            CarePadSettingsMethods.GET_SNAPSHOT -> {
                val reqVersion = extras?.getInt(
                    CarePadSettingsBundleCodec.KEY_CONTRACT_VERSION,
                    CarePadSettingsProtocol.CONTRACT_VERSION
                ) ?: CarePadSettingsProtocol.CONTRACT_VERSION

                if (reqVersion != CarePadSettingsProtocol.CONTRACT_VERSION) {
                    CarePadSettingsBundleCodec.encodeSnapshotIncompatible(
                        supportedContractVersion = CarePadSettingsProtocol.CONTRACT_VERSION,
                        message = "Requested contract version $reqVersion is incompatible with supported ${CarePadSettingsProtocol.CONTRACT_VERSION}"
                    )
                } else {
                    when (val result = onGetSnapshot()) {
                        is CarePadSettingsSnapshotResult.Success -> {
                            CarePadSettingsBundleCodec.encodeSnapshotSuccess(result.snapshot)
                        }
                        is CarePadSettingsSnapshotResult.Unavailable -> {
                            CarePadSettingsBundleCodec.encodeSnapshotUnavailable(result.message)
                        }
                        is CarePadSettingsSnapshotResult.Incompatible -> {
                            CarePadSettingsBundleCodec.encodeSnapshotIncompatible(
                                result.supportedContractVersion,
                                result.message
                            )
                        }
                    }
                }
            }

            CarePadSettingsMethods.WRITE_BOOLEAN -> {
                val request = CarePadSettingsBundleCodec.decodeWriteBooleanRequest(extras)
                if (request == null) {
                    CarePadSettingsBundleCodec.encodeSettingResult(
                        CarePadSettingResult.Unavailable("Malformed WRITE_BOOLEAN request payload")
                    )
                } else if (request.contractVersion != CarePadSettingsProtocol.CONTRACT_VERSION) {
                    CarePadSettingsBundleCodec.encodeSettingResult(
                        CarePadSettingResult.Incompatible(
                            supportedContractVersion = CarePadSettingsProtocol.CONTRACT_VERSION,
                            message = "Contract version ${request.contractVersion} incompatible"
                        )
                    )
                } else {
                    val result = onWriteBoolean(
                        catalogRevision = request.catalogRevision,
                        itemId = request.itemId,
                        value = request.value
                    )
                    CarePadSettingsBundleCodec.encodeSettingResult(result)
                }
            }

            CarePadSettingsMethods.WRITE_SINGLE_CHOICE -> {
                val request = CarePadSettingsBundleCodec.decodeWriteSingleChoiceRequest(extras)
                if (request == null) {
                    CarePadSettingsBundleCodec.encodeSettingResult(
                        CarePadSettingResult.Unavailable("Malformed WRITE_SINGLE_CHOICE request payload")
                    )
                } else if (request.contractVersion != CarePadSettingsProtocol.CONTRACT_VERSION) {
                    CarePadSettingsBundleCodec.encodeSettingResult(
                        CarePadSettingResult.Incompatible(
                            supportedContractVersion = CarePadSettingsProtocol.CONTRACT_VERSION,
                            message = "Contract version ${request.contractVersion} incompatible"
                        )
                    )
                } else {
                    val result = onWriteSingleChoice(
                        catalogRevision = request.catalogRevision,
                        itemId = request.itemId,
                        selectedOptionId = request.selectedOptionId
                    )
                    CarePadSettingsBundleCodec.encodeSettingResult(result)
                }
            }

            else -> {
                CarePadSettingsBundleCodec.encodeSettingResult(
                    CarePadSettingResult.Unavailable("Unsupported settings RPC method '$method'")
                )
            }
        }
    }

    /**
     * Called when the host queries the module for its current settings snapshot.
     */
    abstract fun onGetSnapshot(): CarePadSettingsSnapshotResult

    /**
     * Called when the host requests a boolean setting update.
     */
    abstract fun onWriteBoolean(
        catalogRevision: String,
        itemId: String,
        value: Boolean
    ): CarePadSettingResult

    /**
     * Called when the host requests a single choice setting update.
     */
    abstract fun onWriteSingleChoice(
        catalogRevision: String,
        itemId: String,
        selectedOptionId: String
    ): CarePadSettingResult

    // --- Standard ContentProvider CRUD methods are disabled for RPC provider ---

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
