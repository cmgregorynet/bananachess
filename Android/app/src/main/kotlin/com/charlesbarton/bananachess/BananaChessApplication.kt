package com.charlesbarton.bananachess

import android.app.Application

/**
 * Owns the [AppContainer]: one per process, made the first time something asks for it.
 *
 * Open only so the release unit tests can extend it (src/testRelease); nothing else should.
 */
open class BananaChessApplication : Application() {
    private val lazyContainer = lazy { AppContainer.create(this) }

    val container: AppContainer by lazyContainer

    // Never called on a device, where the process is simply killed. Test runners that make a
    // new application for each test call it, so the next one can open the same files.
    override fun onTerminate() {
        if (lazyContainer.isInitialized()) container.close()
        super.onTerminate()
    }
}
