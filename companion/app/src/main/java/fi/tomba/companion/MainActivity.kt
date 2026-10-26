package fi.tomba.companion

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.core.voiceinvocations.isVoiceInvocationsIntent
import com.meta.wearable.dat.core.voiceinvocations.startVoiceInvocationsStream
import com.meta.wearable.dat.core.voiceinvocations.types.actions.LaunchApp
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * One job: let Meta's own voice-launch command wake the user's default Android assistant.
 * No Claude/ChatGPT credentials or audio pass through Companion.
 *
 * Android and ChatGPT do not guarantee that ACTION_ASSIST opens a *voice* conversation.
 * The "Start with Voice" preference must be enabled in ChatGPT, and the target device
 * must be tested unlocked/locked before claiming hands-free operation.
 */
class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private var channelJob: Job? = null
    private var lastLaunch = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        if (savedInstanceState == null && isVoiceInvocationsIntent(intent)) {
            voiceLaunched()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), BLUETOOTH_REQUEST)
        } else {
            observeRegistration()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isVoiceInvocationsIntent(intent)) voiceLaunched()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == BLUETOOTH_REQUEST) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) observeRegistration()
            else show("Bluetooth-yhteyden lupa tarvitaan Meta-laseja varten")
        }
    }

    private fun buildScreen() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(), 42.dp(), 24.dp(), 24.dp())
        }
        layout.addView(TextView(this).apply {
            text = "Companion"
            textSize = 27f
            setPadding(0, 0, 0, 18.dp())
        })
        status = TextView(this).apply {
            text = "Odotetaan Meta-yhteyttä…"
            textSize = 16f
            setPadding(0, 0, 0, 18.dp())
        }
        layout.addView(status)
        fun addButton(label: String, click: () -> Unit) {
            layout.addView(Button(this).apply { text = label; setOnClickListener { click() } })
        }
        addButton("Yhdistä Meta AI -sovellukseen") { Wearables.startRegistration(this) }
        addButton("Testaa oletusavustajaa") { openDefaultAssistant() }
        addButton("Avaa ChatGPT -sovellus") { openChatGptApp() }
        layout.addView(TextView(this).apply {
            text = "Lasikomento: Hey Meta, start Companion.\n" +
                "ChatGPT: Asetukset > Voice > Start with Voice.\n" +
                "Tämä sovellus ei käynnistä ChatGPT:n ääntä suoraan eikä korvaa Meta AI -sovellusta."
            textSize = 14f
            setPadding(0, 24.dp(), 0, 0)
        })
        setContentView(layout)
    }

    private fun observeRegistration() {
        lifecycleScope.launch {
            Wearables.registrationState.collect { state ->
                show("Meta-rekisteröinti: $state")
                if (state == RegistrationState.REGISTERED) startChannel()
                else channelJob?.cancel()
            }
        }
    }

    private fun startChannel() {
        if (channelJob?.isActive == true) return
        channelJob = lifecycleScope.launch {
            // This is a Wearables-level channel; no camera or microphone permission required.
            val stream = Wearables.startVoiceInvocationsStream(AutoDeviceSelector())
            try {
                launch {
                    stream.state.collect { state -> show("Lasien äänikanava: $state") }
                }
                launch {
                    stream.errors.collect { error ->
                        Log.e("Companion", "Voice invocation: ${error.description}")
                        show("Lasien äänikanavan virhe: ${error.description}")
                    }
                }
                stream.invocations.collect { invocation ->
                    when (invocation) {
                        is LaunchApp -> {
                            // Every invocation must receive exactly one acknowledgement.
                            val opened = openDefaultAssistant()
                            if (opened) invocation.responseHandle.sendSuccess(null)
                            else invocation.responseHandle.sendFailure(null)
                        }
                    }
                }
            } finally {
                stream.close()
            }
        }
    }

    private fun voiceLaunched() {
        openDefaultAssistant()
    }

    private fun openDefaultAssistant(): Boolean {
        // The Meta SDK may deliver both an intent and an invocation for one spoken command.
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunch < 1_500L) return true
        lastLaunch = now
        val assist = Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (startSafely(assist)) {
            show("Androidin oletusavustaja avattu (puhetilaa ei vahvistettu)")
            true
        } else {
            openChatGptApp()
        }
    }

    private fun openChatGptApp(): Boolean {
        val launch = packageManager.getLaunchIntentForPackage("com.openai.chatgpt")
        if (launch == null) {
            show("ChatGPT-sovellusta ei löytynyt. Asenna se ensin.")
            return false
        }
        val opened = startSafely(launch)
        show(if (opened) "ChatGPT avattu (puhetilaa ei vahvistettu)" else "ChatGPT:n avaaminen epäonnistui")
        return opened
    }

    private fun startSafely(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (error: ActivityNotFoundException) {
        Log.w("Companion", "No app handles launch: ${intent.action}")
        false
    } catch (error: SecurityException) {
        Log.w("Companion", "Android blocked launch: ${intent.action}")
        false
    }

    private fun show(message: String) {
        status.text = message
        Log.i("Companion", message)
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
    companion object { private const val BLUETOOTH_REQUEST = 91 }
}
