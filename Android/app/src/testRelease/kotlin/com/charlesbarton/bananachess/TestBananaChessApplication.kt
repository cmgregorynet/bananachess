package com.charlesbarton.bananachess

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import org.robolectric.Shadows.shadowOf

/**
 * The application Robolectric uses for the release unit tests, found by its name.
 *
 * Compose UI tests launch a bare [ComponentActivity]. The debug build declares it through the
 * ui-test-manifest dependency, but the release build must not ship that, and Robolectric reads
 * the release variant's own manifest. So it is registered here, for the tests only.
 */
class TestBananaChessApplication : BananaChessApplication() {
    override fun onCreate() {
        super.onCreate()
        shadowOf(packageManager).addOrUpdateActivity(
            ActivityInfo().also { info ->
                info.name = ComponentActivity::class.java.name
                info.packageName = packageName
                info.exported = true
                info.theme = android.R.style.Theme_Material_Light_NoActionBar
            },
        )
    }
}
