package dev.xiaoman.hands

import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLinksTest {
    @Test
    fun alarmLinkOpensPrefilledNewAlarmPage() {
        val intent = assertNotNull(AppLinks.intentFor(Uri.parse("xiaoman://alarm/set?hour=14&minute=5&label=喝水")))
        assertEquals(AlarmClock.ACTION_SET_ALARM, intent.action)
        assertEquals(14, intent.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
        assertEquals(5, intent.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))
        assertEquals("喝水", intent.getStringExtra(AlarmClock.EXTRA_MESSAGE))
        assertFalse(intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, true))
        assertNull(intent.component)
    }

    @Test
    fun minuteDefaultsToZeroAndLabelIsOptional() {
        val intent = assertNotNull(AppLinks.intentFor(Uri.parse("XIAOMAN://alarm/set/?hour=7")))
        assertEquals(0, intent.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))
        assertFalse(intent.hasExtra(AlarmClock.EXTRA_MESSAGE))
    }

    @Test
    fun otherSchemesAreNotXiaomanLinks() {
        assertNull(AppLinks.intentFor(Uri.parse("amapuri://route/plan/?dname=家")))
    }

    @Test
    fun timerValidatesDurationAndKeepsClockUi() {
        val timer = assertNotNull(AppLinks.intentFor(Uri.parse("xiaoman://timer/set?seconds=300&label=Tea&skipUi=true")))
        assertEquals(AlarmClock.ACTION_SET_TIMER, timer.action)
        assertEquals(300, timer.getIntExtra(AlarmClock.EXTRA_LENGTH, 0))
        assertEquals("Tea", timer.getStringExtra(AlarmClock.EXTRA_MESSAGE))
        assertFalse(timer.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, true))
        assertFailsWith<IllegalArgumentException> { AppLinks.intentFor(Uri.parse("xiaoman://timer/set?seconds=0")) }
    }

    @Test
    fun registeredRoutesUseOnlyPublicSystemActions() {
        assertEquals(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT,
            AppLinks.globalActionFor(Uri.parse("xiaoman://screenshot/take"))?.id,
        )
        assertEquals(
            android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY,
            AppLinks.intentFor(Uri.parse("xiaoman://internet/open"))?.action,
        )
        assertNull(AppLinks.globalActionFor(Uri.parse("https://screenshot/take")))
        assertFailsWith<IllegalArgumentException> { AppLinks.intentFor(Uri.parse("xiaoman://wifi/enable")) }
    }

    @Test
    fun operatorNotesIncludeOnlyAvailableRoutes() {
        val notes = AppLinks.operatorNotes(
            english = true,
            availableActions = setOf(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS),
            canHandle = { it.action == android.provider.Settings.ACTION_DISPLAY_SETTINGS },
            actionLabel = { "Open notifications" },
        )

        kotlin.test.assertTrue("xiaoman://notifications/open" in notes)
        kotlin.test.assertTrue("xiaoman://display/open" in notes)
        assertFalse("xiaoman://screenshot/take" in notes)
        assertFalse("xiaoman://bluetooth/open" in notes)
    }

    @Test
    fun malformedOrUnknownLinksAreRejected() {
        for (link in listOf("xiaoman://alarm/set?hour=24", "xiaoman://alarm/set?minute=5", "xiaoman://alarm/set?hour=8&minute=60",
            "xiaoman://alarm/delete?hour=8", "xiaoman://call/dial?number=110")) {
            assertFailsWith<IllegalArgumentException>(link) { AppLinks.intentFor(Uri.parse(link)) }
        }
    }
}
