package uk.co.dougsbir.firetvremote

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import uk.co.dougsbir.firetvremote.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var adb: AdbClient? = null
    private var busy = false

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
        binding.sendTextButton.setOnClickListener {
            val value = binding.textInput.text.toString()
            if (value.isNotBlank()) {
                sendCommand("input text '" + value.replace("'", "'\\''").replace(" ", "%s") + "'")
                binding.textInput.text?.clear()
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
            binding.sendTextButton).forEach { it.isEnabled = enabled }
        binding.textInput.isEnabled = enabled
    }

    override fun onDestroy() { adb?.close(); super.onDestroy() }
}
