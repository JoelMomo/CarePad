package com.joel.thordoctor.modules.host.catalog

import android.content.Context

internal enum class CatalogModuleInstallStage {
    NONE,
    PREPARING,
    SUBMITTED,
    AWAITING_CONFIRMATION,
    FAILED,
}

internal data class CatalogModuleInstallSnapshot(
    val stage: CatalogModuleInstallStage,
    val detail: String? = null,
)

internal object CatalogModuleInstallState {
    const val ACTION_STATE_CHANGED =
        "com.joel.thordoctor.action.CAREPAD_CATALOG_INSTALL_STATE_CHANGED"

    private const val PREFS = "carepad_catalog_install_state"

    fun read(context: Context, packageName: String): CatalogModuleInstallSnapshot {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stage = runCatching {
            CatalogModuleInstallStage.valueOf(
                prefs.getString("$packageName.stage", null)
                    ?: CatalogModuleInstallStage.NONE.name
            )
        }.getOrDefault(CatalogModuleInstallStage.NONE)
        return CatalogModuleInstallSnapshot(
            stage = stage,
            detail = prefs.getString("$packageName.detail", null),
        )
    }

    fun markPreparing(context: Context, packageName: String) =
        write(context, packageName, CatalogModuleInstallStage.PREPARING, null)

    fun markSubmitted(context: Context, packageName: String) =
        write(context, packageName, CatalogModuleInstallStage.SUBMITTED, null)

    fun markAwaitingConfirmation(context: Context, packageName: String) =
        write(context, packageName, CatalogModuleInstallStage.AWAITING_CONFIRMATION, null)

    fun markFailed(context: Context, packageName: String, detail: String) =
        write(context, packageName, CatalogModuleInstallStage.FAILED, detail)

    fun clear(context: Context, packageName: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove("$packageName.stage")
            .remove("$packageName.detail")
            .apply()
        notifyChanged(context)
    }

    private fun write(
        context: Context,
        packageName: String,
        stage: CatalogModuleInstallStage,
        detail: String?,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("$packageName.stage", stage.name)
            .apply {
                if (detail == null) remove("$packageName.detail")
                else putString("$packageName.detail", detail)
            }
            .apply()
        notifyChanged(context)
    }

    private fun notifyChanged(context: Context) {
        context.sendBroadcast(
            android.content.Intent(ACTION_STATE_CHANGED).setPackage(context.packageName)
        )
    }
}
