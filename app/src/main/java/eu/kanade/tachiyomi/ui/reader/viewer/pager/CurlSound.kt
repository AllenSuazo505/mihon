package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import eu.kanade.tachiyomi.R
import kotlin.math.abs

/**
 * Mihon Gold: gesture-synced paper audio for the page curl.
 *
 * This is deliberately NOT a one-shot effect. While a curl is being dragged, a
 * short paper-rustle loop plays continuously and its volume and playback rate
 * follow the finger: faster movement means a livelier, louder rustle; holding
 * the page still quiets it within a fraction of a second; dragging the curl
 * back and forth keeps rustling because volume follows the magnitude of the
 * movement, not its direction. When the page drops into place, the loop stops
 * and a soft settle sound plays once.
 *
 * The assets are generated noise (no licensed audio): a seamlessly loopable
 * filtered-noise rustle in res/raw, and a short decaying burst for the settle.
 * Playback goes through the media audio stream ([AudioAttributes.USAGE_MEDIA]),
 * so the device's media volume controls it. Kept subtle on purpose: the loop
 * never exceeds [MAX_LOOP_VOLUME], so it stays pleasant across many turns.
 */
class CurlSound(context: Context) {

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private var rustleId = 0
    private var settleId = 0
    private var rustleLoaded = false
    private var settleLoaded = false

    private var loopStreamId = 0
    private var loopPlaying = false

    private var lastProgress = -1f
    private var lastTimeMs = 0L
    private var smoothVelocity = 0f
    private var wasAudible = false

    private val handler = Handler(Looper.getMainLooper())
    private val silenceRunnable = Runnable {
        if (loopPlaying) {
            soundPool.setVolume(loopStreamId, 0f, 0f)
        }
    }

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                if (sampleId == rustleId) rustleLoaded = true
                if (sampleId == settleId) settleLoaded = true
            }
        }
        rustleId = soundPool.load(context, R.raw.page_turn_rustle, 1)
        settleId = soundPool.load(context, R.raw.page_turn_settle, 1)
    }

    /**
     * Call on every curl scroll callback with the overlay's progress 0..1 and the
     * current uptime in milliseconds. Volume and rate follow the smoothed curl
     * velocity (progress units per second).
     */
    fun onCurlProgress(progress: Float, nowMs: Long) {
        handler.removeCallbacks(silenceRunnable)
        if (lastProgress >= 0f && nowMs > lastTimeMs) {
            val dt = (nowMs - lastTimeMs) / 1000f
            val instant = abs(progress - lastProgress) / dt
            smoothVelocity = SMOOTHING * smoothVelocity + (1f - SMOOTHING) * instant
        }
        lastProgress = progress
        lastTimeMs = nowMs
        if (!rustleLoaded) return

        val velocity = smoothVelocity
        val volume = (velocity * VOLUME_PER_VELOCITY).coerceIn(0f, MAX_LOOP_VOLUME)
        val rate = (0.85f + velocity * RATE_PER_VELOCITY).coerceIn(0.85f, 1.5f)
        if (!loopPlaying) {
            loopStreamId = soundPool.play(rustleId, volume, volume, 1, -1, rate)
            loopPlaying = loopStreamId != 0
        } else {
            soundPool.setVolume(loopStreamId, volume, volume)
            soundPool.setRate(loopStreamId, rate)
        }
        if (volume > 0.01f) wasAudible = true
        // Scroll callbacks stop when the finger holds still, so quiet the loop
        // shortly after the last update unless another one arrives first.
        handler.postDelayed(silenceRunnable, STILL_QUIET_DELAY_MS)
    }

    /**
     * Call when the turn settles (pager idle). Stops the rustle loop and, if the
     * turn was audible and [playSettle] is true, plays the soft settle sound once.
     */
    fun onTurnSettled(playSettle: Boolean) {
        handler.removeCallbacks(silenceRunnable)
        if (loopPlaying) {
            soundPool.stop(loopStreamId)
            loopPlaying = false
        }
        smoothVelocity = 0f
        lastProgress = -1f
        if (playSettle && wasAudible && settleLoaded) {
            soundPool.play(settleId, SETTLE_VOLUME, SETTLE_VOLUME, 1, 0, 1.0f)
        }
        wasAudible = false
    }

    fun release() {
        handler.removeCallbacks(silenceRunnable)
        soundPool.release()
    }

    companion object {
        /** Smoothing for the curl velocity (higher = steadier, less twitchy). */
        private const val SMOOTHING = 0.55f

        /** Loop volume gained per unit of curl velocity (progress/second). */
        private const val VOLUME_PER_VELOCITY = 0.12f

        /** Loop volume cap: deliberately subtle across many turns. */
        private const val MAX_LOOP_VOLUME = 0.35f

        /** Playback-rate gain per unit of curl velocity. */
        private const val RATE_PER_VELOCITY = 0.15f

        /** One-shot settle volume. */
        private const val SETTLE_VOLUME = 0.30f

        /** How long after the last scroll callback the loop goes quiet. */
        private const val STILL_QUIET_DELAY_MS = 150L
    }
}
