package dev.synople.glassassistant.fragments

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dev.synople.glassassistant.R
import dev.synople.glassassistant.services.ai.*
import dev.synople.glassassistant.utils.GlassGesture
import dev.synople.glassassistant.utils.GlassGestureDetector
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

/**
 * Fragment that displays AI responses with real-time streaming support
 * Shows text as it arrives from the AI provider
 */
class StreamingResultFragment : Fragment() {

    companion object {
        private const val TAG = "StreamingResultFragment"
        private const val ARG_PROVIDER = "provider"
        private const val ARG_REQUEST = "request"
        private const val ARG_API_KEY = "api_key"
    }

    private lateinit var scrollView: ScrollView
    private lateinit var resultTextView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusTextView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val responseBuilder = StringBuilder()
    private var streamingJob: kotlinx.coroutines.Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_streaming_result, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        scrollView = view.findViewById(R.id.scrollView)
        resultTextView = view.findViewById(R.id.tvResult)
        progressBar = view.findViewById(R.id.progressBar)
        statusTextView = view.findViewById(R.id.tvStatus)

        // Get arguments
        val provider = arguments?.getSerializable(ARG_PROVIDER) as? StreamingAIProvider
        val request = arguments?.getSerializable(ARG_REQUEST) as? AIProvider.AIRequest
        val apiKey = arguments?.getString(ARG_API_KEY)

        if (provider != null && request != null && apiKey != null) {
            startStreaming(provider, request, apiKey)
        } else {
            Log.e(TAG, "Missing required arguments for streaming")
            statusTextView.text = "Error: Missing configuration"
            progressBar.visibility = View.GONE
        }
    }

    private fun startStreaming(
        provider: StreamingAIProvider,
        request: AIProvider.AIRequest,
        apiKey: String
    ) {
        statusTextView.text = "Connecting to ${provider.getProviderName()}..."
        progressBar.visibility = View.VISIBLE
        resultTextView.text = ""
        responseBuilder.clear()

        val callback = object : StreamingCallback {
            override fun onStreamStart() {
                handler.post {
                    statusTextView.text = "Receiving response..."
                    progressBar.visibility = View.VISIBLE
                }
            }

            override fun onChunkReceived(chunk: String) {
                responseBuilder.append(chunk)
                handler.post {
                    resultTextView.text = responseBuilder.toString()
                    // Auto-scroll to bottom
                    scrollView.post {
                        scrollView.fullScroll(ScrollView.FOCUS_DOWN)
                    }
                }
            }

            override fun onComplete(fullResponse: String) {
                handler.post {
                    resultTextView.text = fullResponse
                    statusTextView.text = "Response complete (${fullResponse.length} characters)"
                    progressBar.visibility = View.GONE

                    // Final scroll to top
                    scrollView.post {
                        scrollView.scrollTo(0, 0)
                    }
                }
            }

            override fun onError(error: Exception) {
                Log.e(TAG, "Streaming error", error)
                handler.post {
                    statusTextView.text = "Error: ${error.message}"
                    progressBar.visibility = View.GONE

                    if (responseBuilder.isEmpty()) {
                        resultTextView.text = "Failed to get response: ${error.message}"
                    }
                }
            }

            override fun onProgress(bytesReceived: Long, estimatedTotal: Long) {
                handler.post {
                    if (estimatedTotal > 0) {
                        val progress = (bytesReceived * 100 / estimatedTotal).toInt()
                        progressBar.progress = progress
                        statusTextView.text = "Receiving: $progress%"
                    } else {
                        statusTextView.text = "Received: $bytesReceived bytes"
                    }
                }
            }
        }

        // Launch streaming coroutine
        streamingJob = lifecycleScope.launch {
            try {
                if (provider.isStreamingSupported()) {
                    provider.queryStream(request, apiKey, callback).collect { chunk ->
                        // Chunks are already handled by callback
                        Log.v(TAG, "Chunk collected: ${chunk.length} chars")
                    }
                } else {
                    // Fallback to non-streaming
                    statusTextView.text = "Streaming not supported, using standard mode..."
                    val response = provider.query(request, apiKey)
                    callback.onComplete(response.text)
                }
            } catch (e: Exception) {
                callback.onError(e)
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onGlassGesture(glassGesture: GlassGesture) {
        when (glassGesture.gesture) {
            GlassGestureDetector.Gesture.SWIPE_DOWN -> {
                // Cancel streaming and go back
                streamingJob?.cancel()
                findNavController().popBackStack()
            }
            GlassGestureDetector.Gesture.TAP -> {
                // Toggle auto-scroll
                if (scrollView.canScrollVertically(1)) {
                    scrollView.fullScroll(ScrollView.FOCUS_DOWN)
                } else {
                    scrollView.scrollTo(0, 0)
                }
            }
            else -> {}
        }
    }

    override fun onStart() {
        super.onStart()
        EventBus.getDefault().register(this)
    }

    override fun onStop() {
        EventBus.getDefault().unregister(this)
        streamingJob?.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        streamingJob?.cancel()
    }
}