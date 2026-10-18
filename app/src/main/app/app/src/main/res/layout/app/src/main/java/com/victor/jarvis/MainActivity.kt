package com.victor.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var statusText: TextView
    private lateinit var tts: TextToSpeech
    private val client = OkHttpClient()

    // Paste your own Anthropic API key here. Get one free at console.anthropic.com
    private val apiKey = "YOUR_ANTHROPIC_API_KEY"

    private val speechLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        val heard = results?.get(0) ?: return@registerForActivityResult
        statusText.text = "You said: $heard"
        askClaude(heard)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        tts = TextToSpeech(this, this)

        val listenButton: Button = findViewById(R.id.listenButton)
        listenButton.setOnClickListener { startListening() }

        requestPermissions()
    }

    private fun requestPermissions() {
        val needed = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.SEND_SMS,
            Manifest.permission.CALL_PHONE
        ).filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed, 100)
        }
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your command")
        }
        speechLauncher.launch(intent)
    }

    // Sends the heard text to Claude, asking it to reply with a tiny JSON
    // instruction describing which action to take.
    private fun askClaude(heard: String) {
        statusText.text = "Thinking..."

        val systemPrompt = """
            You control an Android phone. Reply ONLY with compact JSON, no other words, shaped like:
            {"action": "open_app", "target": "whatsapp"}
            {"action": "send_sms", "target": "+1234567890", "message": "hello"}
            {"action": "call", "target": "+1234567890"}
            {"action": "open_url", "target": "https://example.com"}
            {"action": "speak", "message": "your reply to say out loud"}
            Pick exactly one action for the user's request.
        """.trimIndent()

        val body = JSONObject().apply {
            put("model", "claude-sonnet-4-5")
            put("max_tokens", 300)
            put("system", systemPrompt)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", heard)
            }))
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(RequestBody.create("application/json".toMediaType(), body.toString()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { statusText.text = "Network error: ${e.message}" }
            }

            override fun onResponse(call: Call, response: Response) {
                val raw = response.body?.string() ?: return
                try {
                    val json = JSONObject(raw)
                    val text = json.getJSONArray("content").getJSONObject(0).getString("text")
                    val action = JSONObject(text)
                    runOnUiThread { performAction(action) }
                } catch (e: Exception) {
                    runOnUiThread { statusText.text = "Couldn't parse reply" }
                }
            }
        })
    }

    private fun performAction(action: JSONObject) {
        when (action.optString("action")) {
            "open_app" -> openApp(action.optString("target"))
            "send_sms" -> sendSms(action.optString("target"), action.optString("message"))
            "call" -> makeCall(action.optString("target"))
            "open_url" -> openUrl(action.optString("target"))
            "speak" -> speak(action.optString("message"))
            else -> statusText.text = "Didn't understand that"
        }
    }

    private fun openApp(name: String) {
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val match = apps.firstOrNull {
            pm.getApplicationLabel(it).toString().contains(name, ignoreCase = true)
        }
        if (match != null) {
            val launchIntent = pm.getLaunchIntentForPackage(match.packageName)
            startActivity(launchIntent)
            statusText.text = "Opening $name"
        } else {
            statusText.text = "Couldn't find an app called $name"
        }
    }

    private fun sendSms(number: String, message: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:$number")
            putExtra("sms_body", message)
        }
        startActivity(intent)
        statusText.text = "Texting $number"
    }

    private fun makeCall(number: String) {
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:$number")
        }
        startActivity(intent)
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }

    private fun speak(message: String) {
        statusText.text = message
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.getDefault()
        }
    }
}
