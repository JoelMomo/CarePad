package com.joel.thordoctor.modules.host

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import carepad.contracts.CarePadProtocol
import java.io.File

/** Debug-only launcher used to validate the CarePad module contract on real hardware. */
class ModuleLabHarnessActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        continuePendingInstallIfPossible()
        render()
    }

    @Deprecated("Debug-only laboratory uses the platform activity result API for minimal surface area")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SELECT_MODULE_APK || resultCode != RESULT_OK) return
        val uri = data?.data ?: run {
            ModuleLabInstallState.setStatus(this, "No APK was selected.")
            render()
            return
        }
        handleSelectedApk(uri)
    }

    private fun handleSelectedApk(uri: Uri) {
        ModuleLabInstallState.setPendingPath(this, null)
        ModuleLabInstallState.setStatus(this, "Validating selected APK before install/update...")
        render()

        ModuleLabPackageInstaller.copyAndValidate(this, uri).fold(
            onSuccess = { prepared ->
                if (needsUnknownSourcesPermission()) {
                    ModuleLabInstallState.setPendingPath(this, prepared.file.absolutePath)
                    ModuleLabInstallState.setStatus(
                        this,
                        "${validationSummary(prepared)} Enable install permission for CarePad Lab Harness, then return."
                    )
                    openUnknownSourcesSettings()
                } else {
                    submitInstall(prepared)
                }
            },
            onFailure = { error ->
                ModuleLabInstallState.setStatus(
                    this,
                    "APK rejected before install/update: ${error.message ?: error::class.java.simpleName}"
                )
            }
        )
        render()
    }

    private fun continuePendingInstallIfPossible() {
        val pendingPath = ModuleLabInstallState.pendingPath(this) ?: return
        if (needsUnknownSourcesPermission()) {
            ModuleLabInstallState.setStatus(
                this,
                "Install permission is still disabled. Select the APK again or enable permission and return."
            )
            return
        }

        val pendingFile = File(pendingPath)
        runCatching {
            ModuleLabPackageInstaller.validateCachedFile(this, pendingFile)
        }.fold(
            onSuccess = { prepared -> submitInstall(prepared) },
            onFailure = { error ->
                ModuleLabInstallState.setPendingPath(this, null)
                pendingFile.delete()
                ModuleLabInstallState.setStatus(
                    this,
                    "Pending APK is no longer valid: ${error.message ?: error::class.java.simpleName}"
                )
            }
        )
    }

    private fun submitInstall(prepared: PreparedModuleApk) {
        ModuleLabInstallState.setPendingPath(this, null)
        ModuleLabInstallState.setStatus(this, validationSummary(prepared))
        runCatching {
            ModuleLabPackageInstaller.install(this, prepared)
        }.onFailure { error ->
            ModuleLabInstallState.setStatus(
                this,
                "Unable to submit install/update: ${error.message ?: error::class.java.simpleName}"
            )
        }
    }

    private fun requestModuleRemoval(moduleId: String, modulePackageName: String) {
        ModuleLabInstallState.setStatus(
            this,
            "Requesting Android confirmation to remove $moduleId."
        )
        render()

        runCatching {
            startActivity(
                Intent(
                    Intent.ACTION_DELETE,
                    Uri.parse("package:$modulePackageName")
                )
            )
        }.onFailure { error ->
            ModuleLabInstallState.setStatus(
                this,
                "Unable to open Android removal confirmation: ${error.message ?: error::class.java.simpleName}"
            )
            render()
        }
    }

    private fun validationSummary(prepared: PreparedModuleApk): String =
        when (prepared.operation) {
            ModuleLabPackageOperation.INSTALL ->
                "Validated install ${prepared.moduleId} ${prepared.versionName} (versionCode ${prepared.versionCode})."

            ModuleLabPackageOperation.UPDATE ->
                "Validated update ${prepared.moduleId}: ${prepared.previousVersionName} " +
                    "(versionCode ${prepared.previousVersionCode}) -> ${prepared.versionName} " +
                    "(versionCode ${prepared.versionCode})."
        }

    private fun needsUnknownSourcesPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()

    private fun openUnknownSourcesSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:$packageName")
        )
        runCatching { startActivity(intent) }
            .onFailure { error ->
                ModuleLabInstallState.setStatus(
                    this,
                    "Unable to open Android install-source settings: ${error.message ?: error::class.java.simpleName}"
                )
            }
    }

    private fun selectModuleApk() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = APK_MIME_TYPE
            },
            REQUEST_SELECT_MODULE_APK
        )
    }

    private fun render() {
        val result = ModuleManager.discover(this)
        val installPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (packageManager.canRequestPackageInstalls()) "allowed" else "not allowed"
        } else {
            "not required on this Android version"
        }
        val installStatus = ModuleLabInstallState.status(this) ?: "No install/update attempted yet."

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(TextView(context).apply {
                text = "CarePad module laboratory\nHost protocol: ${CarePadProtocol.VERSION}"
                textSize = 22f
            })
            addView(TextView(context).apply {
                text = "\nPackage install permission: $installPermission\nInstall/update status: $installStatus"
            })
            addView(Button(context).apply {
                text = "Select module APK to install or update"
                setOnClickListener { selectModuleApk() }
            })
            addView(Button(context).apply {
                text = "Refresh discovery"
                setOnClickListener { render() }
            })

            if (result.modules.isEmpty()) {
                addView(TextView(context).apply {
                    text = "\nNo compatible trusted modules discovered."
                })
            }

            result.modules.forEach { module ->
                addView(TextView(context).apply {
                    text = buildString {
                        append("\nAccepted: ")
                        append(module.metadata.moduleId)
                        append("\nPackage: ")
                        append(module.packageName)
                        append("\nVersion: ")
                        append(module.metadata.moduleVersion)
                        append("\nProtocol: ")
                        append(module.metadata.protocol.min)
                        append("..")
                        append(module.metadata.protocol.max)
                    }
                })
                addView(Button(context).apply {
                    text = "Open ${module.metadata.moduleId}"
                    setOnClickListener {
                        ModuleManager.open(this@ModuleLabHarnessActivity, module)
                    }
                })
                addView(Button(context).apply {
                    text = "Remove ${module.metadata.moduleId}"
                    setOnClickListener {
                        requestModuleRemoval(module.metadata.moduleId, module.packageName)
                    }
                })
            }

            result.rejected.forEach { rejected ->
                addView(TextView(context).apply {
                    text = "\nRejected: ${rejected.packageName}\nReason: ${rejected.reason}"
                })
            }
        }

        setContentView(
            ScrollView(this).apply {
                addView(
                    content,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        )
    }

    private companion object {
        const val REQUEST_SELECT_MODULE_APK = 2001
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
