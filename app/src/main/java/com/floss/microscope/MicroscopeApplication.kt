package com.floss.microscope

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import com.floss.microscope.core.logging.FlossLogger
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import org.opencv.android.OpenCVLoader
import javax.inject.Inject

@HiltAndroidApp
class MicroscopeApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Logger first, so every later failure is captured with device context.
        FlossLogger.init(this)
        val openCvOk = FlossLogger.timed("Startup", "OpenCVLoader.initLocal") { OpenCVLoader.initLocal() }
        FlossLogger.i("Startup", "OpenCV initLocal=$openCvOk")
        if (!openCvOk) FlossLogger.e("Startup", "OpenCV native libraries failed to load; processing will be unavailable")
    }
}
