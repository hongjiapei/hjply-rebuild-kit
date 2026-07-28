package io.nekohasekai.sfa.vendor

import android.app.Activity
import androidx.camera.core.ImageAnalysis
import io.nekohasekai.sfa.compose.screen.qrscan.QRCodeCropArea
import io.nekohasekai.sfa.update.UpdateInfo
import io.nekohasekai.sfa.update.UpdateSource

object Vendor : VendorInterface {
    override fun checkUpdate(activity: Activity, byUser: Boolean) = Unit

    override fun createQRCodeAnalyzer(
        onSuccess: (String) -> Unit,
        onFailure: (Exception) -> Unit,
        onCropArea: ((QRCodeCropArea?) -> Unit)?,
    ): ImageAnalysis.Analyzer? = null

    override val hasCustomUpdate = false

    override val updateSources = emptyList<UpdateSource>()

    override fun checkUpdateAsync(): UpdateInfo? = null

    override fun scheduleAutoUpdate() = Unit

    override suspend fun verifySilentInstallMethod(method: String): Boolean = false

    override suspend fun downloadAndInstall(context: android.content.Context, downloadUrl: String): Unit =
        throw UnsupportedOperationException("hjply does not support in-app updates")
}
