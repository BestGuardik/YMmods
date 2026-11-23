package com.oleg.chromasync

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var visualizer: Visualizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var currentBass = 0f

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
        }
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")
        webView.loadUrl("file:///android_asset/index.html")

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
        } else {
            initVisualizer()
        }

        MediaBridgeService.onTrackUpdate = { title, artist, cover, accent, playing, dur, pos ->
            handler.post {
                val json = JSONObject().apply {
                    put("title", title)
                    put("artist", artist)
                    put("cover", cover)
                    put("accent", accent)
                    put("playing", playing)
                    put("duration", dur)
                    put("position", pos)
                }
                webView.evaluateJavascript("window.onNativeTrackUpdate && window.onNativeTrackUpdate($json);", null)
            }
        }

        startSyncLoop()
    }

    private fun startSyncLoop() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                MediaBridgeService.refreshSession(this@MainActivity)
                webView.evaluateJavascript("window.onNativeAudioLevel && window.onNativeAudioLevel($currentBass);", null)
                handler.postDelayed(this, 80)
            }
        }, 200)
    }

    private fun initVisualizer() {
        try {
            visualizer = Visualizer(0).apply {
                captureSize = Visualizer.getCaptureSizeRange()[0]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, rate: Int) {
                        if (waveform != null && waveform.isNotEmpty()) {
                            var sum = 0.0
                            for (b in waveform) {
                                val amp = (b.toInt() and 0xFF) - 128
                                sum += amp * amp
                            }
                            val rms = Math.sqrt(sum / waveform.size) / 128.0
                            currentBass = (rms * 2.2).coerceIn(0.0, 1.0).toFloat()
                        }
                    }
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {}
                }, Visualizer.getMaxCaptureRate() / 2, true, false)
                enabled = true
            }
        } catch (_: Exception) {
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            initVisualizer()
        }
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun playPause() {
            val ctrl = MediaBridgeService.activeController ?: return
            val state = ctrl.playbackState?.state
            if (state == android.media.session.PlaybackState.STATE_PLAYING) {
                ctrl.transportControls.pause()
            } else {
                ctrl.transportControls.play()
            }
        }

        @JavascriptInterface
        fun next() {
            MediaBridgeService.activeController?.transportControls?.skipToNext()
        }

        @JavascriptInterface
        fun prev() {
            MediaBridgeService.activeController?.transportControls?.skipToPrevious()
        }

        @JavascriptInterface
        fun seekTo(ms: Long) {
            MediaBridgeService.activeController?.transportControls?.seekTo(ms)
        }

        @JavascriptInterface
        fun openNotificationSettings() {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        @JavascriptInterface
        fun openYandexMusic() {
            val intent = packageManager.getLaunchIntentForPackage("ru.yandex.music")
            if (intent != null) startActivity(intent)
        }
    }

    override fun onDestroy() {
        visualizer?.release()
        super.onDestroy()
    }
}
