package com.joel.thordoctor.modules.host.recovery

import android.content.Context
import com.joel.thordoctor.modules.catalog.recovery.RecoveryProtocolRange
import com.joel.thordoctor.modules.catalog.recovery.RecoverySafety
import com.joel.thordoctor.modules.catalog.recovery.RecoveryTarget

internal data class StoredRecoveryOperation(
    val target: RecoveryTarget,
    val apkPath: String,
    val route: RecoveryExecutionRoute,
    val phase: RecoveryPhase,
    val errorCode: RecoveryErrorCode? = null,
    val detail: String? = null
) {
    fun snapshot(): RecoveryOperationSnapshot = RecoveryOperationSnapshot(
        artifactId = target.artifactId,
        moduleId = target.moduleId,
        packageName = target.packageName,
        targetVersionCode = target.versionCode,
        targetVersionName = target.versionName,
        route = route,
        phase = phase,
        errorCode = errorCode,
        detail = detail
    )
}

/** Persists exactly one in-flight module recovery so system confirmations can outlive Activities. */
internal object RecoveryOperationStore {
    private const val PREFS = "carepad_module_recovery"
    private const val KEY_ARTIFACT_ID = "artifact_id"
    private const val KEY_MODULE_ID = "module_id"
    private const val KEY_PACKAGE_NAME = "package_name"
    private const val KEY_VERSION_CODE = "version_code"
    private const val KEY_VERSION_NAME = "version_name"
    private const val KEY_APK_SHA256 = "apk_sha256"
    private const val KEY_SIGNING_SHA256 = "signing_sha256"
    private const val KEY_PROTOCOL_MIN = "protocol_min"
    private const val KEY_PROTOCOL_MAX = "protocol_max"
    private const val KEY_SOURCES = "sources"
    private const val KEY_RECOVERY_SAFETY = "recovery_safety"
    private const val KEY_APK_PATH = "apk_path"
    private const val KEY_ROUTE = "route"
    private const val KEY_PHASE = "phase"
    private const val KEY_ERROR = "error"
    private const val KEY_DETAIL = "detail"

    fun current(context: Context): StoredRecoveryOperation? = runCatching {
        val prefs = prefs(context)
        val artifactId = prefs.getString(KEY_ARTIFACT_ID, null) ?: return null
        val target = RecoveryTarget(
            artifactId = artifactId,
            moduleId = requireNotNull(prefs.getString(KEY_MODULE_ID, null)),
            packageName = requireNotNull(prefs.getString(KEY_PACKAGE_NAME, null)),
            versionCode = prefs.getLong(KEY_VERSION_CODE, -1L),
            versionName = requireNotNull(prefs.getString(KEY_VERSION_NAME, null)),
            apkSha256 = requireNotNull(prefs.getString(KEY_APK_SHA256, null)),
            signingCertificateSha256 = requireNotNull(prefs.getString(KEY_SIGNING_SHA256, null)),
            protocol = RecoveryProtocolRange(
                min = prefs.getInt(KEY_PROTOCOL_MIN, 0),
                max = prefs.getInt(KEY_PROTOCOL_MAX, 0)
            ),
            sources = prefs.getString(KEY_SOURCES, "")
                .orEmpty()
                .split(SOURCE_SEPARATOR)
                .filter { it.isNotEmpty() },
            recoverySafety = RecoverySafety.valueOf(
                requireNotNull(prefs.getString(KEY_RECOVERY_SAFETY, null))
            )
        )
        StoredRecoveryOperation(
            target = target,
            apkPath = requireNotNull(prefs.getString(KEY_APK_PATH, null)),
            route = RecoveryExecutionRoute.valueOf(
                requireNotNull(prefs.getString(KEY_ROUTE, null))
            ),
            phase = RecoveryPhase.valueOf(
                requireNotNull(prefs.getString(KEY_PHASE, null))
            ),
            errorCode = prefs.getString(KEY_ERROR, null)?.let(RecoveryErrorCode::valueOf),
            detail = prefs.getString(KEY_DETAIL, null)
        )
    }.getOrNull()

    fun savePrepared(
        context: Context,
        target: RecoveryTarget,
        apkPath: String,
        route: RecoveryExecutionRoute
    ): StoredRecoveryOperation {
        val operation = StoredRecoveryOperation(
            target = target,
            apkPath = apkPath,
            route = route,
            phase = RecoveryPhase.PREPARED
        )
        write(context, operation)
        return operation
    }

    fun update(
        context: Context,
        phase: RecoveryPhase,
        errorCode: RecoveryErrorCode? = null,
        detail: String? = null
    ): StoredRecoveryOperation? {
        val current = current(context) ?: return null
        val updated = current.copy(
            phase = phase,
            errorCode = errorCode,
            detail = detail
        )
        write(context, updated)
        return updated
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun write(context: Context, operation: StoredRecoveryOperation) {
        prefs(context).edit()
            .putString(KEY_ARTIFACT_ID, operation.target.artifactId)
            .putString(KEY_MODULE_ID, operation.target.moduleId)
            .putString(KEY_PACKAGE_NAME, operation.target.packageName)
            .putLong(KEY_VERSION_CODE, operation.target.versionCode)
            .putString(KEY_VERSION_NAME, operation.target.versionName)
            .putString(KEY_APK_SHA256, operation.target.normalizedApkSha256)
            .putString(KEY_SIGNING_SHA256, operation.target.normalizedSigningCertificateSha256)
            .putInt(KEY_PROTOCOL_MIN, operation.target.protocol.min)
            .putInt(KEY_PROTOCOL_MAX, operation.target.protocol.max)
            .putString(KEY_SOURCES, operation.target.sources.joinToString(SOURCE_SEPARATOR))
            .putString(KEY_RECOVERY_SAFETY, operation.target.recoverySafety.name)
            .putString(KEY_APK_PATH, operation.apkPath)
            .putString(KEY_ROUTE, operation.route.name)
            .putString(KEY_PHASE, operation.phase.name)
            .apply {
                if (operation.errorCode == null) remove(KEY_ERROR)
                else putString(KEY_ERROR, operation.errorCode.name)
                if (operation.detail == null) remove(KEY_DETAIL)
                else putString(KEY_DETAIL, operation.detail)
            }
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val SOURCE_SEPARATOR = "\u001f"
}
