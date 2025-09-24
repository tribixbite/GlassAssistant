package dev.synople.glassassistant.fragments

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.example.glassassistant.services.InstallationHistoryService
import dev.synople.glassassistant.R
import dev.synople.glassassistant.utils.GlassGesture
import dev.synople.glassassistant.utils.GlassGestureDetector
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.io.File
import java.io.FileInputStream
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

private val TAG = QrApkInstallerFragment::class.simpleName!!

class QrApkInstallerFragment : Fragment() {

    private lateinit var barcodeView: DecoratedBarcodeView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var instructionText: TextView

    private var downloadId: Long = -1
    private lateinit var downloadManager: DownloadManager
    private var downloadedApkUri: Uri? = null

    private lateinit var installationHistory: InstallationHistoryService
    private var currentInstallEntry: InstallationHistoryService.InstallationEntry? = null
    private var lastScannedQrCode: String? = null

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    handleDownloadComplete()
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_qr_apk_installer, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        barcodeView = view.findViewById(R.id.barcodeScanner)
        statusText = view.findViewById(R.id.tvStatus)
        progressBar = view.findViewById(R.id.progressBar)
        instructionText = view.findViewById(R.id.tvInstruction)

        downloadManager = requireContext().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        installationHistory = InstallationHistoryService(requireContext())

        setupBarcodeScanner()
        checkPermissions()

        // Display security stats
        showSecurityStats()
    }

    private fun showSecurityStats() {
        val stats = installationHistory.getStatistics()
        val suspiciousCount = stats["suspicious"] as Int
        if (suspiciousCount > 0) {
            Toast.makeText(requireContext(),
                "⚠️ $suspiciousCount suspicious installations detected",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun setupBarcodeScanner() {
        instructionText.text = "Scan QR code containing APK URL"
        statusText.text = "Ready to scan..."
        progressBar.visibility = View.GONE

        val callback = object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) {
                handleBarcodeResult(result.text)
            }

            override fun possibleResultPoints(resultPoints: MutableList<com.google.zxing.ResultPoint>) {
                // Required by interface but not used
            }
        }

        barcodeView.decodeContinuous(callback)
    }

    private fun handleBarcodeResult(qrContent: String) {
        Log.d(TAG, "QR Code detected: $qrContent")

        // Stop scanning
        barcodeView.pause()
        lastScannedQrCode = qrContent

        // Check if it's a valid URL
        if (isValidApkUrl(qrContent)) {
            statusText.text = "APK URL detected!"
            showDownloadConfirmation(qrContent)
        } else if (qrContent.startsWith("http://") || qrContent.startsWith("https://")) {
            // It's a URL but might not be an APK - still allow download
            statusText.text = "URL detected - checking..."
            checkUrlForApk(qrContent)
        } else {
            statusText.text = "Invalid QR code - not a URL"
            // Resume scanning after 2 seconds
            barcodeView.postDelayed({
                barcodeView.resume()
            }, 2000)
        }
    }

    private fun isValidApkUrl(url: String): Boolean {
        return (url.startsWith("http://") || url.startsWith("https://")) &&
                url.endsWith(".apk", ignoreCase = true)
    }

    private fun checkUrlForApk(url: String) {
        Thread {
            try {
                val connection = URL(url).openConnection() as HttpsURLConnection
                connection.requestMethod = "HEAD"
                connection.connect()

                val contentType = connection.contentType
                val contentDisposition = connection.getHeaderField("Content-Disposition")

                requireActivity().runOnUiThread {
                    if (contentType == "application/vnd.android.package-archive" ||
                        contentDisposition?.contains(".apk") == true ||
                        url.contains(".apk", ignoreCase = true)
                    ) {
                        showDownloadConfirmation(url)
                    } else {
                        statusText.text = "URL does not point to an APK file"
                        barcodeView.postDelayed({
                            barcodeView.resume()
                        }, 2000)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking URL", e)
                requireActivity().runOnUiThread {
                    statusText.text = "Error checking URL: ${e.message}"
                    barcodeView.postDelayed({
                        barcodeView.resume()
                    }, 2000)
                }
            }
        }.start()
    }

    private fun showDownloadConfirmation(url: String) {
        statusText.text = "APK found: ${url.substringAfterLast('/')}"
        instructionText.text = "Tap to download and install\nSwipe down to cancel"

        // Wait for user confirmation
        // This will be handled by gesture events
        downloadedApkUri = Uri.parse(url)
    }

    private fun startApkDownload(url: String) {
        try {
            progressBar.visibility = View.VISIBLE
            statusText.text = "Downloading APK..."
            instructionText.text = "Please wait..."

            // Create installation history entry
            currentInstallEntry = installationHistory.createDownloadEntry(url, lastScannedQrCode)
            installationHistory.logInstallation(currentInstallEntry!!)

            val fileName = "downloaded_${System.currentTimeMillis()}.apk"
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("Downloading APK")
                .setDescription(url.substringAfterLast('/'))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            downloadId = downloadManager.enqueue(request)

            // Update entry to downloading status
            currentInstallEntry = currentInstallEntry?.copy(
                installationStatus = InstallationHistoryService.InstallationStatus.DOWNLOADING
            )
            currentInstallEntry?.let { installationHistory.logInstallation(it) }

            // Register receiver for download completion
            requireContext().registerReceiver(
                downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )

            // Start monitoring download progress
            monitorDownloadProgress()

        } catch (e: Exception) {
            Log.e(TAG, "Error starting download", e)
            statusText.text = "Download failed: ${e.message}"
            progressBar.visibility = View.GONE

            // Log failure
            currentInstallEntry?.let {
                val failedEntry = installationHistory.markFailed(it, e.message ?: "Unknown error")
                installationHistory.logInstallation(failedEntry)
            }
        }
    }

    private fun monitorDownloadProgress() {
        Thread {
            var downloading = true
            while (downloading) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)

                if (cursor.moveToFirst()) {
                    val bytesDownloaded = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    )
                    val bytesTotal = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    )
                    val status = cursor.getInt(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                    )

                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        downloading = false
                    } else if (status == DownloadManager.STATUS_FAILED) {
                        downloading = false
                        requireActivity().runOnUiThread {
                            statusText.text = "Download failed"
                            progressBar.visibility = View.GONE
                        }
                    } else if (bytesTotal > 0) {
                        val progress = (bytesDownloaded * 100 / bytesTotal).toInt()
                        requireActivity().runOnUiThread {
                            progressBar.progress = progress
                            statusText.text = "Downloading: $progress%"
                        }
                    }
                }
                cursor.close()

                Thread.sleep(500)
            }
        }.start()
    }

    private fun handleDownloadComplete() {
        progressBar.visibility = View.GONE
        statusText.text = "Download complete!"
        instructionText.text = "Tap to install APK"

        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = downloadManager.query(query)

        if (cursor.moveToFirst()) {
            val uriString = cursor.getString(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
            )
            downloadedApkUri = Uri.parse(uriString)

            // Get file size and calculate hash
            val file = File(Uri.parse(uriString).path!!)
            val fileSize = file.length()
            val sha256 = calculateSHA256(file)

            // Update installation entry with download details
            currentInstallEntry?.let {
                currentInstallEntry = installationHistory.updateDownloadComplete(
                    it, fileSize, sha256
                )
                installationHistory.logInstallation(currentInstallEntry!!)

                // Extract and verify package info
                verifyApkPackage(file)
            }
        }
        cursor.close()

        // Auto-install after 1 second
        view?.postDelayed({
            installApk()
        }, 1000)
    }

    private fun calculateSHA256(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val fis = FileInputStream(file)
            val byteArray = ByteArray(1024)
            var bytesCount: Int

            while (fis.read(byteArray).also { bytesCount = it } != -1) {
                digest.update(byteArray, 0, bytesCount)
            }
            fis.close()

            val bytes = digest.digest()
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating SHA256", e)
            null
        }
    }

    private fun verifyApkPackage(file: File) {
        try {
            val packageInfo = requireContext().packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.GET_SIGNATURES
            )

            packageInfo?.let { info ->
                val packageName = info.packageName
                val versionName = info.versionName
                val versionCode = info.versionCode

                // Check signature validity (simplified - in production, compare with known good signatures)
                val signatureValid = info.signatures?.isNotEmpty() == true

                currentInstallEntry?.let {
                    currentInstallEntry = installationHistory.updatePackageInfo(
                        it, packageName, versionName, versionCode, signatureValid
                    )
                    installationHistory.logInstallation(currentInstallEntry!!)

                    if (!signatureValid) {
                        Toast.makeText(
                            requireContext(),
                            "⚠️ Warning: APK signature could not be verified",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying APK package", e)
        }
    }

    private fun installApk() {
        downloadedApkUri?.let { uri ->
            try {
                // Update status to installing
                currentInstallEntry?.let {
                    currentInstallEntry = it.copy(
                        installationStatus = InstallationHistoryService.InstallationStatus.INSTALLING
                    )
                    installationHistory.logInstallation(currentInstallEntry!!)
                }

                val intent = Intent(Intent.ACTION_VIEW)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    // For Android N and above, use FileProvider
                    val file = File(uri.path!!)
                    val apkUri = FileProvider.getUriForFile(
                        requireContext(),
                        "${requireContext().packageName}.fileprovider",
                        file
                    )
                    intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                } else {
                    intent.setDataAndType(uri, "application/vnd.android.package-archive")
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }

                startActivity(intent)

                statusText.text = "Installing APK..."
                instructionText.text = "Follow system prompts to complete installation"

                // Mark as successful (user will complete installation)
                currentInstallEntry?.let {
                    val successEntry = installationHistory.markSuccess(it)
                    installationHistory.logInstallation(successEntry)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error installing APK", e)
                statusText.text = "Installation failed: ${e.message}"

                // Log failure
                currentInstallEntry?.let {
                    val failedEntry = installationHistory.markFailed(it, e.message ?: "Unknown error")
                    installationHistory.logInstallation(failedEntry)
                }
            }
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onGlassGesture(glassGesture: GlassGesture) {
        when (glassGesture.gesture) {
            GlassGestureDetector.Gesture.TAP -> {
                // If we have a URL ready, start download
                downloadedApkUri?.let { uri ->
                    if (uri.toString().startsWith("http")) {
                        startApkDownload(uri.toString())
                        downloadedApkUri = null
                    } else {
                        // APK already downloaded, install it
                        installApk()
                    }
                }
            }
            GlassGestureDetector.Gesture.SWIPE_DOWN -> {
                // Cancel and go back
                findNavController().popBackStack()
            }
            else -> {}
        }
    }

    override fun onResume() {
        super.onResume()
        barcodeView.resume()
    }

    override fun onPause() {
        super.onPause()
        barcodeView.pause()
    }

    override fun onStart() {
        super.onStart()
        EventBus.getDefault().register(this)
    }

    override fun onStop() {
        EventBus.getDefault().unregister(this)
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            requireContext().unregisterReceiver(downloadReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
    }

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 100
    }
}