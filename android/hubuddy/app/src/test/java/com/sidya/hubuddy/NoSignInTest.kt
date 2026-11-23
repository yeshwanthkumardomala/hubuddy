package com.sidya.hubuddy

import android.content.Intent
import android.view.View
import com.sidya.hubuddy.ui.LoginActivity
import com.sidya.hubuddy.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The app opens straight to the dashboard: no login number, no PIN, unless the user turns the app lock on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21, 28, 34])
class NoSignInTest {
    @Before fun setUp() { HubuddyApp.cipherOverride = TestCipher(); HubuddyApp.lockOverride = false }
    @After fun tearDown() { HubuddyApp.lockOverride = true }

    private fun app() = RuntimeEnvironment.getApplication() as HubuddyApp

    @Test fun startingTheAppGoesStraightToTheDashboard() {
        val login = Robolectric.buildActivity(LoginActivity::class.java).create().get()
        val next = shadowOf(login).nextStartedActivity
        assertEquals(MainActivity::class.java.name, next.component!!.className)
        assertTrue(login.isFinishing)
        assertTrue(app().auth.canAccessDashboard())
        assertFalse(app().auth.lockEnabled)
    }

    @Test fun nothingExpiresOrAsksForAPinWhileTheLockIsOff() {
        val m = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertFalse(m.isFinishing)
        assertNull("no login screen was started", shadowOf(m).nextStartedActivity)
        assertNotNull(m.window.decorView.findViewWithTag<View>("nav_Settings"))
    }

    @Test fun theLockCanBeTurnedOnWithYourOwnPinAndOffAgain() {
        val a = app().auth
        assertEquals("The two PINs do not match.", a.enableLock("246801", "246800"))
        assertTrue(a.enableLock("123456", "123456")!!.contains("sequence"))
        assertTrue(a.enableLock("111111", "111111")!!.contains("repeated"))
        assertNull(a.enableLock("246801", "246801"))
        assertTrue(a.lockEnabled); assertTrue(a.canAccessDashboard())
        a.logout()
        assertFalse("locked until the PIN is entered", a.canAccessDashboard())
        val login = Robolectric.buildActivity(LoginActivity::class.java).create().get()
        assertNotNull("the sign-in form appears only now", login.window.decorView.findViewWithTag<View>("loginButton"))
        assertFalse(a.disableLock("000000"))
        assertTrue(a.lockEnabled)
        assertTrue(a.disableLock("246801"))
        assertFalse(a.lockEnabled); assertTrue(a.canAccessDashboard())
    }

    @Test fun settingsOffersTheSwitchInTheRightState() {
        val m = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        m.window.decorView.findViewWithTag<View>("nav_Settings")!!.performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNotNull(m.window.decorView.findViewWithTag<View>("lockOn"))
        assertNull(m.window.decorView.findViewWithTag<View>("lockOff"))
        app().auth.enableLock("246801", "246801")
        m.rerender()
        assertNotNull(m.window.decorView.findViewWithTag<View>("lockOff"))
        assertNull(m.window.decorView.findViewWithTag<View>("lockOn"))
    }
}
