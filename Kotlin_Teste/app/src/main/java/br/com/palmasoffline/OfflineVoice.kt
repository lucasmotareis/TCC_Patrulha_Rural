package br.com.palmasoffline

import android.content.Context
import android.speech.tts.TextToSpeech
import com.stadiamaps.ferrostar.core.AndroidTtsObserver
import com.stadiamaps.ferrostar.core.AndroidTtsStatusListener
import com.stadiamaps.ferrostar.core.SpokenInstructionObserver
import kotlinx.coroutines.flow.StateFlow
import uniffi.ferrostar.SpokenInstruction

/** Voice selection explicitly rejects online and not-installed voices. */
class OfflineVoice(context: Context, private val report: (String?) -> Unit) : SpokenInstructionObserver {
    @Volatile var ready = false
        private set
    @Volatile var sessionActive = false
    private var foreground = false
    private val delegate = AndroidTtsObserver(context, statusObserver = object : AndroidTtsStatusListener {
        override fun onTtsInitialized(tts: TextToSpeech?, status: Int) {
            val voice = tts?.voices?.filter {
                !it.isNetworkConnectionRequired && it.locale.language == "pt" &&
                    !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }?.sortedByDescending { it.locale.country == "BR" }?.firstOrNull()
            ready = foreground && status == TextToSpeech.SUCCESS && voice != null && tts.setVoice(voice) == TextToSpeech.SUCCESS
            if (!ready) delegateMuted()
            report(if (ready) null else "Voz portuguesa offline indisponível. A orientação visual continua funcionando.")
        }
        override fun onTtsShutdownAndRelease() { ready = false }
        override fun onTtsSpeakError(utteranceId: String, status: Int) { report("Falha na voz. Continue pelas instruções visuais.") }
    })
    private fun delegateMuted() { delegate.setMuted(true) }
    fun start() { foreground = true; delegate.start() }
    fun shutdown() { foreground = false; sessionActive = false; ready = false; delegate.stopAndClearQueue(); delegate.shutdown() }
    override val muteState: StateFlow<Boolean> get() = delegate.muteState
    override fun setMuted(isMuted: Boolean) { delegate.setMuted(isMuted || !ready) }
    override fun stopAndClearQueue() { delegate.stopAndClearQueue() }
    override fun onSpokenInstructionTrigger(spokenInstruction: SpokenInstruction) {
        if (ready && sessionActive) delegate.onSpokenInstructionTrigger(spokenInstruction)
    }
}
