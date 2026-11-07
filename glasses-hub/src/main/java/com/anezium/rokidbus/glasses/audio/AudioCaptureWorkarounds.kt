package com.anezium.rokidbus.glasses.audio

import android.annotation.SuppressLint
import android.app.Application
import android.app.Instrumentation
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Build
import android.os.Looper
import android.os.Parcel
import java.lang.ref.WeakReference

/** API 31/32 shell capture only. Source: Genymobile/scrcpy, server/src/main/java/com/genymobile/scrcpy/. */
// Runs only under app_process as the shell uid, where the app hidden-API policy lint models does not apply.
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi", "MissingPermission", "WrongConstant")
internal object AudioCaptureWorkarounds {
    // Derived from scrcpy (Apache-2.0), Workarounds.java (ActivityThread initialization and app info).
    fun shellContext(): Context {
        check(Build.VERSION.SDK_INT in 31..32) { "Audio capture requires API 31/32" }
        // The framework creates Handlers on the calling thread while building the system context.
        Looper.prepareMainLooper()
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        fun set(name: String, value: Any?) = threadClass.getDeclaredField(name).apply { isAccessible = true }.set(thread, value)
        threadClass.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, thread)
        set("mSystemThread", true)
        val configuration = Class.forName("android.app.ConfigurationController")
            .getDeclaredConstructor(Class.forName("android.app.ActivityThreadInternal"))
            .apply { isAccessible = true }.newInstance(thread)
        set("mConfigurationController", configuration)
        val bindClass = Class.forName("android.app.ActivityThread\$AppBindData")
        val bind = bindClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        bindClass.getDeclaredField("appInfo").apply { isAccessible = true }
            .set(bind, ApplicationInfo().apply { packageName = "com.android.shell" })
        set("mBoundApplication", bind)
        val systemContext = threadClass.getDeclaredMethod("getSystemContext").apply { isAccessible = true }.invoke(thread) as Context
        val context = ShellContext(systemContext)
        set("mInitialApplication", Instrumentation.newApplication(Application::class.java, context))
        return context
    }

    // Derived from scrcpy (Apache-2.0), FakeContext.java (shell package and attribution).
    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName() = "com.android.shell"
        override fun getOpPackageName() = "com.android.shell"
        override fun getApplicationContext(): Context = this
        override fun getAttributionSource(): AttributionSource = AttributionSource.Builder(2000).setPackageName(packageName).build()
    }

    // Derived from scrcpy (Apache-2.0), Workarounds.java#createAudioRecord, API 31/32 branch only.
    fun createAudioRecord(context: Context): AudioRecord {
        check(Build.VERSION.SDK_INT in 31..32)
        val cls = AudioRecord::class.java
        val recorder = cls.getDeclaredConstructor(Long::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(0L)
        try {
            fun set(name: String, value: Any?) = cls.getDeclaredField(name).apply { isAccessible = true }.set(recorder, value)
            fun call(name: String, types: Array<Class<*>>, vararg values: Any?): Any? =
                cls.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(recorder, *values)
            val intType = Int::class.javaPrimitiveType!!
            set("mRecordingState", AudioRecord.RECORDSTATE_STOPPED)
            set("mInitializationLooper", null)
            val builder = AudioAttributes.Builder()
            builder.javaClass.getMethod("setInternalCapturePreset", intType).invoke(builder, 8) // REMOTE_SUBMIX
            val attributes = builder.build()
            set("mAudioAttributes", attributes)
            call("audioParamCheck", arrayOf(intType, intType, intType), 8, 48000, AudioFormat.ENCODING_PCM_16BIT)
            set("mChannelCount", 2)
            set("mChannelMask", AudioFormat.CHANNEL_IN_STEREO)
            val minimum = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "AudioRecord minimum buffer: $minimum" }
            val bufferSize = minimum * 8
            call("audioBuffSizeCheck", arrayOf(intType), bufferSize)
            val rates = intArrayOf(48000)
            val sessions = intArrayOf(0)
            val state = AttributionSource::class.java.getDeclaredMethod("asScopedParcelState").apply { isAccessible = true }
                .invoke(context.attributionSource) as AutoCloseable
            state.use {
                val parcel = it.javaClass.getDeclaredMethod("getParcel").apply { isAccessible = true }.invoke(it) as Parcel
                val result = call("native_setup", arrayOf(Any::class.java, Any::class.java, IntArray::class.java,
                    intType, intType, intType, intType, IntArray::class.java, Parcel::class.java,
                    Long::class.javaPrimitiveType!!, intType), WeakReference(recorder), attributes, rates,
                    AudioFormat.CHANNEL_IN_STEREO, 0, AudioFormat.ENCODING_PCM_16BIT, bufferSize, sessions, parcel, 0L, 0) as Int
                check(result == 0) { "AudioRecord native_setup returned $result" }
            }
            set("mSampleRate", rates[0])
            set("mSessionId", sessions[0])
            set("mState", AudioRecord.STATE_INITIALIZED)
            return recorder
        } catch (failure: Throwable) {
            recorder.release()
            throw failure
        }
    }
}
