package com.anezium.rokidbus.phone

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The assist button and the native scenes of one Hi Rokid session. The hub changes it only on the
 * main thread: CxrGlobal delivers scene reports and presses there, and every end of the session
 * (link down, glasses BT down, hub stop) runs or is posted there too, so a report or press from a
 * session that is over cannot land after [clear]. Work a press schedules carries the session's
 * [generation] and stops once that session has ended.
 */
internal class GlassesAssistSession {
    enum class Press { LINK_DOWN, TAKEOVER_PAUSED, NATIVE_SCENE, ALREADY_HELD, TAKE_OVER }

    @Volatile var scenes: Set<String> = emptySet()
        private set

    @Volatile var scenesShape: String = ""
        private set

    private val held = AtomicBoolean(false)
    private val currentGeneration = AtomicInteger(0)
    private val pressCount = AtomicInteger(0)

    val generation: Int
        get() = currentGeneration.get()

    fun isCurrent(generation: Int): Boolean = currentGeneration.get() == generation

    /** Identifies the press [onPressStart] last took over. */
    val press: Int
        get() = pressCount.get()

    fun isHeld(press: Int): Boolean = held.get() && pressCount.get() == press

    val isHeld: Boolean
        get() = held.get()

    /** Returns whether the running scenes or the report's shape changed. */
    fun onScenesReport(scenesJson: String?): Boolean {
        val running = NativeSceneTakeover.running(scenesJson)
        val shape = NativeSceneTakeover.shape(scenesJson)
        if (running == scenes && shape == scenesShape) return false
        scenes = running
        scenesShape = shape
        return true
    }

    /**
     * Every gate runs before the press is held: a press left to Rokid gets no exit echo of ours,
     * so a held one would swallow the next press.
     */
    fun onPressStart(linkUp: Boolean, takeoverEnabled: Boolean): Press = when {
        !linkUp -> Press.LINK_DOWN
        !takeoverEnabled -> Press.TAKEOVER_PAUSED
        NativeSceneTakeover.blocksAssistantTakeover(scenes) -> Press.NATIVE_SCENE
        !held.compareAndSet(false, true) -> Press.ALREADY_HELD
        else -> {
            pressCount.incrementAndGet()
            Press.TAKE_OVER
        }
    }

    fun onPressStop() {
        held.set(false)
    }

    /** A native scene reported after the press began; the remaining exit shots must not hit it. */
    fun nativeSceneRunning(): Boolean = NativeSceneTakeover.blocksAssistantTakeover(scenes)

    /** Ends the session. Returns whether a press was held, so plugins can be told it is released. */
    fun clear(): Boolean {
        currentGeneration.incrementAndGet()
        scenes = emptySet()
        scenesShape = ""
        return held.getAndSet(false)
    }
}
