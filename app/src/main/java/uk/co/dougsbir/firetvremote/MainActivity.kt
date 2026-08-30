package uk.co.dougsbir.firetvremote

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import uk.co.dougsbir.firetvremote.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var adb: AdbClient? = null
    private var busy = false

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val words = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            words?.firstOrNull()?.let { sendText(it) }
        }
    }

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { allowed ->
        if (allowed) startSpeech()
        else Toast.makeText(this, "Microphone permission is needed", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.ipInput.setText(getPreferences(MODE_PRIVATE).getString("last_ip", ""))
        binding.connectButton.setOnClickListener { if (adb == null) connect() else disconnect() }
        mapOf(
            binding.upButton to 19, binding.downButton to 20, binding.leftButton to 21,
            binding.rightButton to 22, binding.selectButton to 23, binding.backButton to 4,
            binding.homeButton to 3, binding.rewindButton to 89,
            binding.playPauseButton to 85, binding.fastForwardButton to 90,
            binding.volumeDownButton to 25, binding.volumeUpButton to 24, binding.muteButton to 164
        ).forEach { (button, code) -> button.setOnClickListener { sendCommand("input keyevent " + code) } }
        binding.menuButton.setOnClickListener {
            sendCommand("input keyevent --longpress KEYCODE_MENU")
        }
        binding.sendTextButton.setOnClickListener {
            sendText(binding.textInput.text.toString())
        }
        binding.micButton.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startSpeech()
            } else {
                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        setControlsEnabled(false)
    }

    private fun connect() {
        if (busy) return
        val address = binding.ipInput.text.toString().trim()
        if (address.isBlank()) { binding.ipInput.error = "Enter the Fire TV IP address"; return }
        busy = true
        binding.connectButton.isEnabled = false
        binding.statusText.text = "Connecting… Watch the TV for approval"
        lifecycleScope.launch {
            try {
                adb = AdbClient.connect(this@MainActivity, address, 5555)
                getPreferences(MODE_PRIVATE).edit().putString("last_ip", address).apply()
                binding.statusText.text = "Connected to " + address
                binding.connectButton.text = getString(R.string.disconnect)
                setControlsEnabled(true)
            } catch (e: Exception) {
                binding.statusText.text = "Could not connect"
                Toast.makeText(this@MainActivity, e.message ?: "Connection failed", Toast.LENGTH_LONG).show()
                adb?.close(); adb = null
            } finally { busy = false; binding.connectButton.isEnabled = true }
        }
    }

    private fun disconnect() {
        adb?.close(); adb = null
        binding.statusText.text = "Not connected"
        binding.connectButton.text = getString(R.string.connect)
        setControlsEnabled(false)
    }

    private fun sendCommand(command: String) {
        val client = adb ?: return
        lifecycleScope.launch {
            try { client.shell(command) }
            catch (_: Exception) {
                Toast.makeText(this@MainActivity, "Connection lost", Toast.LENGTH_SHORT).show()
                disconnect()
            }
        }
    }

    private fun sendText(value: String) {
        val codes = value.mapNotNull { keyCodeFor(it) }
        if (codes.isEmpty()) {
            Toast.makeText(this, "Type or speak some text first", Toast.LENGTH_SHORT).show()
            return
        }
        sendCommand("input keyevent " + codes.joinToString(" "))
        binding.textInput.text?.clear()
    }

    private fun startSpeech() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak text for Fire TV")
        }
        try { speechLauncher.launch(intent) }
        catch (_: Exception) {
            Toast.makeText(this, "Speech recognition is not available", Toast.LENGTH_LONG).show()
        }
    }

    private fun keyCodeFor(char: Char): Int? = when {
        char in 'a'..'z' -> 29 + (char - 'a')
        char in 'A'..'Z' -> 29 + (char - 'A')
        char in '0'..'9' -> if (char == '0') 7 else 7 + (char - '0')
        char == ' ' -> 62
        char == '.' -> 56
        char == ',' -> 55
        char == '-' -> 69
        char == '@' -> 77
        char == '/' -> 76
        else -> null
    }

    private fun setControlsEnabled(enabled: Boolean) {
        listOf(binding.upButton, binding.downButton, binding.leftButton, binding.rightButton,
            binding.selectButton, binding.backButton, binding.homeButton, binding.menuButton,
            binding.rewindButton, binding.playPauseButton, binding.fastForwardButton,
            binding.volumeDownButton, binding.volumeUpButton, binding.muteButton,
            binding.sendTextButton, binding.micButton).forEach { it.isEnabled = enabled }
        binding.textInput.isEnabled = enabled
    }

    override fun onDestroy() { adb?.close(); super.onDestroy() }
}
