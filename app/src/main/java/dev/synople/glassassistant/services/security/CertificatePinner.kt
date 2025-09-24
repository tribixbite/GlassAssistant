package dev.synople.glassassistant.services.security

import android.util.Base64
import android.util.Log
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.net.ssl.*

/**
 * Certificate pinning implementation for secure API connections
 * Validates SSL certificates against known pins to prevent MITM attacks
 */
class CertificatePinner {

    companion object {
        private const val TAG = "CertificatePinner"

        // SHA-256 pins for known API providers (Base64 encoded)
        // These should be updated when certificates rotate
        private val PINNED_CERTIFICATES = mapOf(
            "openai.com" to listOf(
                "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", // Primary
                "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="  // Backup
            ),
            "anthropic.com" to listOf(
                "sha256/CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC=",
                "sha256/DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD="
            ),
            "openrouter.ai" to listOf(
                "sha256/EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE=",
                "sha256/FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF="
            ),
            "googleapis.com" to listOf(
                "sha256/GGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGG=",
                "sha256/HHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHH="
            )
        )

        // Grace period for certificate rotation (days)
        private const val ROTATION_GRACE_PERIOD_DAYS = 30
    }

    private var trustManager: X509TrustManager? = null
    private val certificateCache = mutableMapOf<String, List<String>>()
    private var pinningEnabled = true
    private var strictMode = false // When true, reject if no pins match

    data class PinningResult(
        val isValid: Boolean,
        val reason: String? = null,
        val certificateChain: List<String>? = null
    )

    /**
     * Enable or disable certificate pinning
     */
    fun setPinningEnabled(enabled: Boolean) {
        pinningEnabled = enabled
        Log.i(TAG, "Certificate pinning ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Set strict mode - when enabled, connections fail if no pins match
     */
    fun setStrictMode(enabled: Boolean) {
        strictMode = enabled
        Log.i(TAG, "Strict mode ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Create an SSLContext with certificate pinning
     */
    fun createPinnedSSLContext(): SSLContext {
        val context = SSLContext.getInstance("TLS")

        // Create custom trust manager
        val trustManagers = arrayOf<TrustManager>(createPinnedTrustManager())

        context.init(null, trustManagers, java.security.SecureRandom())
        return context
    }

    /**
     * Create a trust manager that performs certificate pinning
     */
    private fun createPinnedTrustManager(): X509TrustManager {
        // Get default trust manager
        val trustManagerFactory = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        )
        trustManagerFactory.init(null as java.security.KeyStore?)

        val trustManagers = trustManagerFactory.trustManagers
        val defaultTrustManager = trustManagers.firstOrNull { it is X509TrustManager }
            as? X509TrustManager
            ?: throw IllegalStateException("No X509TrustManager found")

        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                defaultTrustManager.checkClientTrusted(chain, authType)
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                // First, perform standard validation
                defaultTrustManager.checkServerTrusted(chain, authType)

                // Then perform pinning if enabled
                if (pinningEnabled && chain != null && chain.isNotEmpty()) {
                    val result = validateCertificateChain(chain)
                    if (!result.isValid) {
                        throw SSLException("Certificate pinning failed: ${result.reason}")
                    }
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> {
                return defaultTrustManager.acceptedIssuers
            }
        }
    }

    /**
     * Validate a certificate chain against pinned certificates
     */
    fun validateCertificateChain(chain: Array<out X509Certificate>): PinningResult {
        if (!pinningEnabled) {
            return PinningResult(true, "Pinning disabled")
        }

        if (chain.isEmpty()) {
            return PinningResult(false, "Empty certificate chain")
        }

        try {
            val leafCert = chain[0]
            val hostname = extractHostname(leafCert)

            // Get pins for this hostname
            val expectedPins = getPinsForHost(hostname)

            if (expectedPins.isEmpty()) {
                return if (strictMode) {
                    PinningResult(false, "No pins configured for host: $hostname")
                } else {
                    Log.w(TAG, "No pins configured for host: $hostname, allowing connection")
                    PinningResult(true, "No pins configured, non-strict mode")
                }
            }

            // Calculate pins for the certificate chain
            val actualPins = chain.map { cert ->
                calculatePin(cert)
            }

            // Check if any actual pin matches expected pins
            val matchFound = actualPins.any { actualPin ->
                expectedPins.contains(actualPin)
            }

            return if (matchFound) {
                Log.d(TAG, "Certificate pin matched for $hostname")
                PinningResult(true, "Pin matched", actualPins)
            } else {
                Log.e(TAG, "Certificate pin mismatch for $hostname")
                Log.e(TAG, "Expected: $expectedPins")
                Log.e(TAG, "Actual: $actualPins")
                PinningResult(false, "Pin mismatch", actualPins)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error validating certificate chain", e)
            return PinningResult(false, "Validation error: ${e.message}")
        }
    }

    /**
     * Calculate SHA-256 pin for a certificate
     */
    private fun calculatePin(cert: X509Certificate): String {
        val publicKey = cert.publicKey.encoded
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKey)
        return "sha256/${Base64.encodeToString(hash, Base64.NO_WRAP)}"
    }

    /**
     * Extract hostname from certificate
     */
    private fun extractHostname(cert: X509Certificate): String {
        // Try to extract from Subject Alternative Names
        try {
            val altNames = cert.subjectAlternativeNames
            if (altNames != null) {
                for (altName in altNames) {
                    if (altName.size >= 2 && altName[0] == 2) { // DNS name
                        return altName[1] as String
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract SAN", e)
        }

        // Fallback to CN from Subject
        val subject = cert.subjectDN.name
        val cnMatch = Regex("CN=([^,]+)").find(subject)
        return cnMatch?.groupValues?.get(1) ?: "unknown"
    }

    /**
     * Get pins for a specific host
     */
    private fun getPinsForHost(hostname: String): List<String> {
        // Check cache first
        certificateCache[hostname]?.let { return it }

        // Find pins by matching hostname patterns
        for ((pattern, pins) in PINNED_CERTIFICATES) {
            if (hostname.endsWith(pattern)) {
                certificateCache[hostname] = pins
                return pins
            }
        }

        return emptyList()
    }

    /**
     * Update pins for a specific host (for dynamic pinning)
     */
    fun updatePins(hostname: String, pins: List<String>) {
        certificateCache[hostname] = pins
        Log.i(TAG, "Updated pins for $hostname: ${pins.size} pins")
    }

    /**
     * Clear all cached pins
     */
    fun clearCache() {
        certificateCache.clear()
        Log.i(TAG, "Certificate pin cache cleared")
    }

    /**
     * Get current pins for debugging
     */
    fun getCurrentPins(): Map<String, List<String>> {
        return PINNED_CERTIFICATES.toMap()
    }

    /**
     * Verify a connection's certificates
     */
    fun verifyConnection(connection: HttpsURLConnection): Boolean {
        return try {
            connection.connect()

            val certs = connection.serverCertificates
            if (certs.isNotEmpty()) {
                val x509Certs = certs.filterIsInstance<X509Certificate>().toTypedArray()
                val result = validateCertificateChain(x509Certs)
                result.isValid
            } else {
                Log.w(TAG, "No certificates found in connection")
                !strictMode
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying connection", e)
            false
        }
    }

    /**
     * Apply pinning to an HttpsURLConnection
     */
    fun applyPinning(connection: HttpsURLConnection) {
        if (pinningEnabled) {
            connection.sslSocketFactory = createPinnedSSLContext().socketFactory
            connection.hostnameVerifier = HostnameVerifier { hostname, session ->
                // Verify hostname
                val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
                val hostnameValid = defaultVerifier.verify(hostname, session)

                if (hostnameValid && pinningEnabled) {
                    // Additional certificate validation
                    try {
                        val certs = session.peerCertificates
                        val x509Certs = certs.filterIsInstance<X509Certificate>().toTypedArray()
                        val result = validateCertificateChain(x509Certs)
                        result.isValid
                    } catch (e: Exception) {
                        Log.e(TAG, "Certificate validation failed", e)
                        !strictMode
                    }
                } else {
                    hostnameValid
                }
            }
        }
    }
}