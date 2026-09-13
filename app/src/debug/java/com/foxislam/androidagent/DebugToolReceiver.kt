package com.foxislam.androidagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.foxislam.androidagent.agent.Outcome
import com.foxislam.androidagent.agent.Tools
import com.foxislam.androidagent.control.ControlService
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Debug-only. Runs a single tool from adb so the device-control layer can be exercised
 * without a model in the loop:
 *
 *   adb shell am broadcast -a com.foxislam.androidagent.TOOL \
 *     -n com.foxislam.androidagent/.DebugToolReceiver --es tool ui_tree --es args '{}'
 */
class DebugToolReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val tool = intent.getStringExtra("tool") ?: return
        val args = parse(intent.getStringExtra("args"))

        val service = ControlService.instance
        if (service == null) {
            Log.e(TAG, "RESULT $tool :: accessibility service is not connected")
            return
        }

        service.scope.launch {
            val result = runCatching { Tools(service, context).call(tool, args) }
                .fold(::render) { "EXCEPTION ${it.javaClass.simpleName}: ${it.message}" }
            // Chunked because logcat truncates a single long line
            Log.i(TAG, "RESULT $tool ::")
            result.chunked(3_000).forEach { Log.i(TAG, it) }
            Log.i(TAG, "END $tool")
        }
    }

    private fun render(outcome: Outcome): String = when (outcome) {
        is Outcome.Text -> outcome.text
        is Outcome.Done -> "DONE ${outcome.summary}"
        is Outcome.Image -> {
            // Write out exactly the bytes the model would receive, so what the agent sees
            // can be inspected
            val path = "/sdcard/Download/agent-capture.jpg"
            runCatching {
                java.io.File(path).writeBytes(android.util.Base64.decode(outcome.base64, android.util.Base64.NO_WRAP))
            }
            "IMAGE ${outcome.note} base64Length=${outcome.base64.length} saved=$path"
        }
    }

    private fun parse(raw: String?): Map<String, Any?> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { key ->
                json.get(key).takeUnless { it == JSONObject.NULL }
            }
        }.getOrElse { emptyMap() }
    }

    private companion object {
        const val TAG = "DebugTool"
    }
}
