package io.gatekeeper.util

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.provider.DocumentsContract

object FileShuttleExternalBrowser {
    private const val ANDROID_PACKAGE = "android"
    private const val GOOGLE_FILES_PACKAGE = "com.google.android.apps.nbu.files"
    private const val CLIP_LABEL = "Gatekeeper File Shuttle"
    private const val MIME_FOLDER = "resource/folder"
    private const val MIME_ROOT = "vnd.android.document/root"
    private val documentsUiPackages = listOf(
        "com.google.android.documentsui",
        "com.android.documentsui",
        "com.google.android.go.documentsui",
    )
    private val unsupportedPackages = setOf(
        ANDROID_PACKAGE,
        "com.mixplorer",
        "com.mixplorer.silver",
    )

    fun findTargets(context: Context): List<ResolveInfo> =
        context.packageManager
            .queryIntentActivities(browseIntent(), PackageManager.MATCH_DEFAULT_ONLY)
            .filter {
                it.activityInfo.packageName != context.packageName &&
                    it.activityInfo.packageName !in unsupportedPackages
            }
            .distinctBy { it.activityInfo.packageName to it.activityInfo.name }

    fun isGoogleFiles(packageName: String?): Boolean = packageName == GOOGLE_FILES_PACKAGE

    fun documentsRootIntent(context: Context): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(
            DocumentsContract.buildRootUri(
                CrossProfileDocumentsProvider.AUTHORITY,
                CrossProfileDocumentsProvider.DUMMY_ROOT,
            ),
            MIME_ROOT,
        )
        documentsUiPackages.firstOrNull { packageName ->
            context.packageManager.resolveActivity(
                Intent(this).setPackage(packageName),
                PackageManager.MATCH_SYSTEM_ONLY,
            ) != null
        }?.let(::setPackage)
    }

    fun browseIntent(component: ComponentName? = null): Intent {
        val documentUri = DocumentsContract.buildDocumentUri(
            CrossProfileDocumentsProvider.AUTHORITY,
            CrossProfileDocumentsProvider.DUMMY_ROOT,
        )
        val treeUri = DocumentsContract.buildTreeDocumentUri(
            CrossProfileDocumentsProvider.AUTHORITY,
            CrossProfileDocumentsProvider.DUMMY_ROOT,
        )
        val treeDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            CrossProfileDocumentsProvider.DUMMY_ROOT,
        )
        val grantedUris = ClipData.newRawUri(CLIP_LABEL, documentUri).apply {
            addItem(ClipData.Item(treeUri))
        }

        return Intent(Intent.ACTION_VIEW).apply {
            this.component = component
            setDataAndType(treeDocumentUri, MIME_FOLDER)
            clipData = grantedUris
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
        }
    }
}
