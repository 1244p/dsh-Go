package io.github.ccch1mneyyy.dsh_app.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The list arithmetic the module relies on. `ENABLED_ACCESSIBILITY_SERVICES` is a `:`
 * joined list and every other accessibility service on the phone lives in it too, so the two
 * things that can go wrong — dropping someone else's entry, or appending a duplicate every time
 * the ROM writes — are both pinned here.
 */
class AccessibilityListTest {
    private val mine = Component("io.github.ccch1mneyyy.dsh_app", "io.github.ccch1mneyyy.dsh_app.phone.a11y.PhoneA11yService")

    @Test fun findsOurEntryInFullForm() {
        val list = "com.example.other/com.example.other.Service:${mine.entry}"
        assertTrue(AccessibilityList.contains(list, mine))
    }

    @Test fun findsOurEntryInTheShortFormTheRomWrites() {
        // ColorOS and Settings both abbreviate a class in the package itself to ".Name".
        assertTrue(AccessibilityList.contains("io.github.ccch1mneyyy.dsh_app/.phone.a11y.PhoneA11yService", mine))
    }

    @Test fun aDifferentServiceInOurPackageIsNotOurs() {
        assertFalse(AccessibilityList.contains("io.github.ccch1mneyyy.dsh_app/.phone.a11y.OtherService", mine))
        assertFalse(AccessibilityList.contains("com.other/.phone.a11y.PhoneA11yService", mine))
    }

    @Test fun anEmptyListBecomesJustOurEntry() {
        assertEquals(mine.entry, AccessibilityList.ensure(null, mine))
        assertEquals(mine.entry, AccessibilityList.ensure("", mine))
    }

    @Test fun appendingKeepsEveryOtherEntryExactly() {
        val other = "com.example.other/com.example.other.Service"
        assertEquals("$other:${mine.entry}", AccessibilityList.ensure(other, mine))
    }

    @Test fun aListThatAlreadyNamesUsIsReturnedAsTheSameInstance() {
        val list = "com.example.other/com.example.other.Service:${mine.entry}"
        assertSame(list, AccessibilityList.ensure(list, mine))
    }

    @Test fun theShortFormIsAlsoLeftAloneRatherThanDuplicated() {
        val list = "io.github.ccch1mneyyy.dsh_app/.phone.a11y.PhoneA11yService"
        assertSame(list, AccessibilityList.ensure(list, mine))
    }

    @Test fun entriesWithoutASlashAreIgnored() {
        assertFalse(AccessibilityList.contains("garbage:.alsoGarbage", mine))
        assertEquals(
            "garbage:${mine.entry}",
            AccessibilityList.ensure("garbage", mine),
        )
    }

    /**
     * The regression that matter most: the first version of the boot repair read an empty list (the
     * provider not loaded yet) and wrote back "just us", deleting every other accessibility service
     * on the phone. An empty or missing value must come back untouched.
     */
    @Test fun anEmptyOrMissingListIsNeverTurnedIntoJustUs() {
        assertEquals(null, AccessibilityList.ensureWhenPresent(null, mine))
        assertEquals("", AccessibilityList.ensureWhenPresent("", mine))
    }

    @Test fun aRealListStillGetsUsAdded() {
        val other = "com.example.other/com.example.other.Service"
        assertEquals("$other:${mine.entry}", AccessibilityList.ensureWhenPresent(other, mine))
    }

    @Test fun aRealListThatAlreadyNamesUsIsReturnedAsTheSameInstance() {
        val list = "com.example.other/com.example.other.Service:${mine.entry}"
        assertSame(list, AccessibilityList.ensureWhenPresent(list, mine))
    }

    /** [AccessibilityProtection.keep] is the whole rule the settings hook applies, minus the switch. */
    @Test fun theHookRuleLeavesAnEmptyValueAloneAndAddsUsToOneThatHasContent() {
        assertEquals(null, AccessibilityProtection.keep(null, mine))
        assertEquals("", AccessibilityProtection.keep("", mine))
        val other = "com.example.other/com.example.other.Service"
        assertEquals("$other:${mine.entry}", AccessibilityProtection.keep(other, mine))
    }
}
