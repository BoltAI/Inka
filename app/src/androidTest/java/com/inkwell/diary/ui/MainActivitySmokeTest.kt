package com.inkwell.diary.ui

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import com.inkwell.diary.data.Prefs
import org.junit.Test

class MainActivitySmokeTest {
    @Test
    fun launchesIntoOnboardingWhenIncomplete() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = Prefs(context)
        val previousOnboardingState = prefs.onboardingComplete
        prefs.onboardingComplete = false
        val launchIntent = Intent().setClassName(context.packageName, MainActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        try {
            ActivityScenario.launch<MainActivity>(launchIntent).use {
                onView(withText("A diary that writes back.")).check(matches(withText("A diary that writes back.")))
            }
        } finally {
            prefs.onboardingComplete = previousOnboardingState
        }
    }
}
