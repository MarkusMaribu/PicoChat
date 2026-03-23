package com.markusmaribu.picochat.util

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import com.markusmaribu.picochat.R

class SoundManager(context: Context) {

    enum class Sound {
        JOIN, SCROLL, INVALID, SELECT_PEN, SELECT_ERASER,
        BIG_BRUSH, SMALL_BRUSH, SELECT_LAYOUT, KEY_DOWN, KEY_UP,
        SEND, CLEAR, PEN, ERASER, ENTER_ROOM, LEAVE_ROOM,
        SELECT, CONFIRM, EXPORT_SUCCESS, SYMBOL_DROP,
        ONLINE_SEARCHING, FAILURE, ONLINE_FOUND,
        RECEIVED
    }

    private val soundPool: SoundPool
    private val sounds = mutableMapOf<Sound, Int>()
    private val loadedIds = mutableSetOf<Int>()
    private var drawingStreamId: Int = 0
    private var loopingStreamId: Int = 0

    init {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(6)
            .setAudioAttributes(attrs)
            .build()

        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                loadedIds.add(sampleId)
            } else {
                val name = sounds.entries.find { it.value == sampleId }?.key
                Log.w(TAG, "Failed to load sound $name (id=$sampleId, status=$status)")
            }
        }

        sounds[Sound.JOIN] = soundPool.load(context, R.raw.join_full, 1)
        sounds[Sound.SCROLL] = soundPool.load(context, R.raw.scroll, 1)
        sounds[Sound.INVALID] = soundPool.load(context, R.raw.invalid, 1)
        sounds[Sound.SELECT_PEN] = soundPool.load(context, R.raw.select_pen, 1)
        sounds[Sound.SELECT_ERASER] = soundPool.load(context, R.raw.select_eraser, 1)
        sounds[Sound.BIG_BRUSH] = soundPool.load(context, R.raw.big_brush, 1)
        sounds[Sound.SMALL_BRUSH] = soundPool.load(context, R.raw.small_brush, 1)
        sounds[Sound.SELECT_LAYOUT] = soundPool.load(context, R.raw.select_layout, 1)
        sounds[Sound.KEY_DOWN] = soundPool.load(context, R.raw.key_down, 1)
        sounds[Sound.KEY_UP] = soundPool.load(context, R.raw.key_up, 1)
        sounds[Sound.SEND] = soundPool.load(context, R.raw.send, 1)
        sounds[Sound.CLEAR] = soundPool.load(context, R.raw.clear, 1)
        sounds[Sound.PEN] = soundPool.load(context, R.raw.pen, 1)
        sounds[Sound.ERASER] = soundPool.load(context, R.raw.eraser, 1)
        sounds[Sound.ENTER_ROOM] = soundPool.load(context, R.raw.enter_room, 1)
        sounds[Sound.LEAVE_ROOM] = soundPool.load(context, R.raw.leave_room, 1)
        sounds[Sound.SELECT] = soundPool.load(context, R.raw.select, 1)
        sounds[Sound.CONFIRM] = soundPool.load(context, R.raw.confirm, 1)
        sounds[Sound.EXPORT_SUCCESS] = soundPool.load(context, R.raw.export_success, 1)
        sounds[Sound.SYMBOL_DROP] = soundPool.load(context, R.raw.symbol_drop, 1)
        sounds[Sound.ONLINE_SEARCHING] = soundPool.load(context, R.raw.online_searching, 1)
        sounds[Sound.FAILURE] = soundPool.load(context, R.raw.failure, 1)
        sounds[Sound.ONLINE_FOUND] = soundPool.load(context, R.raw.online_found, 1)
        sounds[Sound.RECEIVED] = soundPool.load(context, R.raw.recieved, 1)
    }

    fun play(sound: Sound) {
        val id = sounds[sound] ?: return
        if (id !in loadedIds) {
            Log.w(TAG, "Sound $sound not yet loaded (id=$id)")
            return
        }
        soundPool.play(id, 1f, 1f, 1, 0, 1f)
    }

    fun playDrawing(sound: Sound) {
        stopDrawing()
        val id = sounds[sound] ?: return
        if (id !in loadedIds) return
        drawingStreamId = soundPool.play(id, 1f, 1f, 1, 0, 1f)
    }

    fun stopDrawing() {
        if (drawingStreamId != 0) {
            soundPool.stop(drawingStreamId)
            drawingStreamId = 0
        }
    }

    fun playLooping(sound: Sound) {
        stopLooping()
        val id = sounds[sound] ?: return
        if (id !in loadedIds) return
        loopingStreamId = soundPool.play(id, 1f, 1f, 1, -1, 1f)
    }

    fun stopLooping() {
        if (loopingStreamId != 0) {
            soundPool.stop(loopingStreamId)
            loopingStreamId = 0
        }
    }

    fun release() {
        soundPool.release()
    }

    companion object {
        private const val TAG = "SoundManager"
    }
}
