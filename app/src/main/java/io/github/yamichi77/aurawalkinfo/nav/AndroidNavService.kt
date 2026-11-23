package io.github.yamichi77.aurawalkinfo.nav

import android.content.Context
import android.content.pm.PackageManager
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.coroutines.runInterruptible

internal fun androidNavService(context: Context): NavService = NavApi(
    credentials = { readCredentials(context) },
    quota = DailyNavQuota(context.getSharedPreferences("nav_quota", Context.MODE_PRIVATE)),
    transport = NavTransport { request ->
        runInterruptible {
            val connection = URL(request.url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.doOutput = true
                request.headers.forEach(connection::setRequestProperty)
                val body = request.body.toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                NavResponse(status, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
            } finally {
                connection.disconnect()
            }
        }
    },
)

private fun readCredentials(context: Context): NavCredentials {
    val manager = context.packageManager
    val packageName = context.packageName
    try {
        val metadata = manager.getApplicationInfo(
            packageName, PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
        ).metaData
        val key = metadata?.getString("com.google.android.geo.API_KEY")
            ?.takeUnless { it.isBlank() || it == "DEFAULT_API_KEY" } ?: throw NavFailure(NavError.PERMISSION)
        val signature = manager.getPackageInfo(
            packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        ).signingInfo?.apkContentsSigners?.singleOrNull() ?: throw NavFailure(NavError.PERMISSION)
        val digest = MessageDigest.getInstance("SHA-1").digest(signature.toByteArray())
        return NavCredentials(key, packageName, HexFormat.of().withUpperCase().formatHex(digest))
    } catch (_: PackageManager.NameNotFoundException) {
        throw NavFailure(NavError.PERMISSION)
    }
}
