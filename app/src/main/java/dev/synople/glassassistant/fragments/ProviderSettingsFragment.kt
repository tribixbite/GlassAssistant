package dev.synople.glassassistant.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dev.synople.glassassistant.R
import dev.synople.glassassistant.security.SecureStorage
import dev.synople.glassassistant.services.ai.*
import dev.synople.glassassistant.utils.GlassGesture
import dev.synople.glassassistant.utils.GlassGestureDetector
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

private val TAG = ProviderSettingsFragment::class.simpleName!!

class ProviderSettingsFragment : Fragment() {

    private lateinit var providerSpinner: Spinner
    private lateinit var modelSpinner: Spinner
    private lateinit var baseUrlEditText: EditText
    private lateinit var apiKeyEditText: EditText
    private lateinit var saveButton: Button
    private lateinit var testButton: Button
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar

    private lateinit var secureStorage: SecureStorage
    private var currentProvider: AIProvider? = null
    private val providers = mutableMapOf<String, AIProvider>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_provider_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        secureStorage = SecureStorage(requireContext())
        secureStorage.migrateUnencryptedData() // Migrate any existing unencrypted data

        initializeViews(view)
        initializeProviders()
        loadCurrentSettings()
        setupListeners()
    }

    private fun initializeViews(view: View) {
        providerSpinner = view.findViewById(R.id.spinnerProvider)
        modelSpinner = view.findViewById(R.id.spinnerModel)
        baseUrlEditText = view.findViewById(R.id.etBaseUrl)
        apiKeyEditText = view.findViewById(R.id.etApiKey)
        saveButton = view.findViewById(R.id.btnSave)
        testButton = view.findViewById(R.id.btnTest)
        statusText = view.findViewById(R.id.tvStatus)
        progressBar = view.findViewById(R.id.progressBar)
    }

    private fun initializeProviders() {
        // Initialize available providers
        providers["OpenRouter"] = OpenRouterProvider()
        providers["OpenAI"] = OpenAIProvider()
        providers["Claude"] = ClaudeProvider()
        providers["Gemini"] = GeminiProvider()
        providers["Local/Custom"] = CustomProvider()

        // Setup provider spinner
        val providerNames = providers.keys.toList()
        val providerAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            providerNames
        )
        providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        providerSpinner.adapter = providerAdapter
    }

    private fun loadCurrentSettings() {
        // Load saved provider settings from secure storage
        val savedProvider = secureStorage.getCurrentProvider()
        val savedBaseUrl = secureStorage.getProviderBaseUrl(savedProvider) ?: ""
        val savedApiKey = secureStorage.getApiKey(savedProvider) ?: ""
        val savedModel = secureStorage.getProviderModel(savedProvider) ?: ""

        // Set spinner to saved provider
        val providerPosition = (providerSpinner.adapter as ArrayAdapter<String>)
            .getPosition(savedProvider)
        if (providerPosition >= 0) {
            providerSpinner.setSelection(providerPosition)
        }

        baseUrlEditText.setText(savedBaseUrl)
        apiKeyEditText.setText(savedApiKey)

        // Load models for selected provider
        loadModelsForProvider(savedProvider, savedModel)
    }

    private fun setupListeners() {
        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val providerName = parent.getItemAtPosition(position) as String
                onProviderSelected(providerName)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        saveButton.setOnClickListener {
            saveSettings()
        }

        testButton.setOnClickListener {
            testConnection()
        }
    }

    private fun onProviderSelected(providerName: String) {
        currentProvider = providers[providerName]
        currentProvider?.let { provider ->
            val config = provider.getConfiguration()

            // Set default base URL if empty
            if (baseUrlEditText.text.isEmpty()) {
                baseUrlEditText.setText(config.baseUrl)
            }

            // Enable/disable base URL editing based on provider
            baseUrlEditText.isEnabled = providerName == "OpenRouter" || providerName == "Local/Custom"

            // Load models for this provider
            loadModelsForProvider(providerName, null)
        }
    }

    private fun loadModelsForProvider(providerName: String, selectedModel: String?) {
        lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            statusText.text = "Loading models..."

            try {
                val provider = providers[providerName]
                val models = provider?.getAvailableModels() ?: emptyList()

                val modelNames = models.map { it.name }
                val modelAdapter = ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_spinner_item,
                    modelNames
                )
                modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                modelSpinner.adapter = modelAdapter

                // Select saved model if available
                selectedModel?.let { model ->
                    val modelPosition = modelAdapter.getPosition(model)
                    if (modelPosition >= 0) {
                        modelSpinner.setSelection(modelPosition)
                    }
                }

                statusText.text = "Models loaded"
            } catch (e: Exception) {
                Log.e(TAG, "Error loading models", e)
                statusText.text = "Error loading models: ${e.message}"
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun saveSettings() {
        val providerName = providerSpinner.selectedItem as String
        val baseUrl = baseUrlEditText.text.toString()
        val apiKey = apiKeyEditText.text.toString()
        val model = modelSpinner.selectedItem as? String ?: ""

        // Validate inputs
        if (apiKey.isEmpty() && providers[providerName]?.getConfiguration()?.requiresApiKey == true) {
            statusText.text = "API key is required"
            return
        }

        // Save to secure storage
        secureStorage.saveProviderSettings(providerName, model, baseUrl)
        if (apiKey.isNotEmpty()) {
            secureStorage.saveApiKey(providerName, apiKey)
        }

        // Update provider with custom base URL if applicable
        if (providerName == "OpenRouter" && baseUrl.isNotEmpty()) {
            providers["OpenRouter"] = OpenRouterProvider(baseUrl)
        } else if (providerName == "Local/Custom" && baseUrl.isNotEmpty()) {
            (providers["Local/Custom"] as? CustomProvider)?.setBaseUrl(baseUrl)
        }

        statusText.text = "Settings saved securely!"
        Toast.makeText(context, "Provider settings saved", Toast.LENGTH_SHORT).show()

        // Navigate back after a short delay
        view?.postDelayed({
            findNavController().popBackStack()
        }, 1000)
    }

    private fun testConnection() {
        val apiKey = apiKeyEditText.text.toString()
        if (apiKey.isEmpty()) {
            statusText.text = "Enter API key first"
            return
        }

        lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            statusText.text = "Testing connection..."

            try {
                val isValid = currentProvider?.validateApiKey(apiKey) ?: false
                if (isValid) {
                    statusText.text = "✓ Connection successful!"
                } else {
                    statusText.text = "✗ Invalid API key or connection failed"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error testing connection", e)
                statusText.text = "Error: ${e.message}"
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onGlassGesture(glassGesture: GlassGesture) {
        when (glassGesture.gesture) {
            GlassGestureDetector.Gesture.TAP -> {
                // Save settings on tap
                saveSettings()
            }
            GlassGestureDetector.Gesture.SWIPE_DOWN -> {
                // Go back without saving
                findNavController().popBackStack()
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
        super.onStop()
    }
}