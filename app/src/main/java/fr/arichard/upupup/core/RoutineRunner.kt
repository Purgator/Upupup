package fr.arichard.upupup.core

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log

/**
 * Runs an alarm's after-stop routine: open an app, speak a phrase aloud, or hand a
 * query to the voice assistant / web search. Everything runs on the application
 * context so it survives the ring screen finishing.
 */
object RoutineRunner {

    private const val TAG = "RoutineRunner"

    fun run(context: Context, type: RoutineType, value: String?) {
        val app = context.applicationContext
        when (type) {
            RoutineType.NONE -> Unit
            RoutineType.APP -> openApp(app, value)
            RoutineType.SPEAK -> speak(app, value)
            RoutineType.ASSISTANT -> assistant(app, value)
        }
    }

    private fun openApp(context: Context, packageName: String?) {
        packageName ?: return
        context.packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    /** Opens the assistant (empty query) or asks it a question via a web search. */
    private fun assistant(context: Context, query: String?) {
        val intent = if (query.isNullOrBlank()) {
            Intent(Intent.ACTION_VOICE_COMMAND)
        } else {
            Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            // Some devices lack a voice assistant: fall back to a plain web search.
            if (!query.isNullOrBlank()) return
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_WEB_SEARCH)
                        .putExtra(SearchManager.QUERY, "")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    /** One-shot TextToSpeech that releases itself once the phrase finishes. */
    private fun speak(context: Context, text: String?) {
        if (text.isNullOrBlank()) return
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TTS init failed ($status)")
                tts?.shutdown()
                return@TextToSpeech
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    tts?.shutdown()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    tts?.shutdown()
                }
            })
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "upupup-routine")
        }
    }
}
