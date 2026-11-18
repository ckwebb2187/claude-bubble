package com.claudebubble.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper

/**
 * Keeps the bubble out of the way of alarms, ringtones and calls.
 *
 * The app itself never records audio (it doesn't even have the microphone permission),
 * never plays sound and never asks for audio focus. This guard covers the one thing left:
 * a web page in the floating chat playing audio. The moment Android starts an alarm,
 * a ringtone, or a call (or the phone switches into call mode), [onAlert] fires so the
 * floating chat can silence itself, and [alertActive] stays true until it's over.
 */
class AudioGuard(context: Context, private val onAlert: () -> Unit) {

    private val am = context.getSystemService(AudioManager::class.java)!!
    private val main = Handler(Looper.getMainLooper())

    var alertActive = false
        private set

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            check(configs)
        }
    }

    fun start() {
        am.registerAudioPlaybackCallback(callback, main)
        check(am.activePlaybackConfigurations)
    }

    fun stop() {
        runCatching { am.unregisterAudioPlaybackCallback(callback) }
    }

    /** Also true while the phone is ringing or in a call, even with nothing playing. */
    fun inCall(): Boolean = am.mode != AudioManager.MODE_NORMAL

    private fun check(configs: List<AudioPlaybackConfiguration>) {
        val loud = configs.any {
            when (it.audioAttributes.usage) {
                AudioAttributes.USAGE_ALARM,
                AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
                AudioAttributes.USAGE_VOICE_COMMUNICATION,
                AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING -> true
                else -> false
            }
        } || inCall()
        val wasActive = alertActive
        alertActive = loud
        if (loud && !wasActive) onAlert()
    }
}
