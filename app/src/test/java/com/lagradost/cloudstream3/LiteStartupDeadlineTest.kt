package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.ui.lite.LiteStartupDeadline
import org.junit.Assert.*
import org.junit.Test

class LiteStartupDeadlineTest {
    @Test fun timedOutAttemptWaitsForAlternativeUntilPlaybackActuallyStarts() {
        val deadline=LiteStartupDeadline(0)
        assertTrue(deadline.update(7000,true,false,0))
        assertTrue("Some buffer after expiry is not actual playback",deadline.update(8000,true,false,100))
        assertFalse(deadline.update(9000,false,false,200))
        assertTrue(deadline.update(10000,true,false,300))
        assertFalse(deadline.update(11000,true,true,400))
    }
    @Test fun idleLimitRequiresSevenActiveSeconds() {
        val deadline=LiteStartupDeadline(0)
        assertFalse(deadline.update(6999,true,false,0))
        assertTrue(deadline.update(7000,true,false,0))
    }
    @Test fun bufferProgressResetsIdleButNotAbsoluteBudget() {
        val deadline=LiteStartupDeadline(0)
        assertFalse(deadline.update(6000,true,false,1000))
        assertFalse(deadline.update(12000,true,false,2000))
        assertFalse(deadline.update(18000,true,false,3000))
        assertFalse(deadline.update(24000,true,false,4000))
        assertFalse(deadline.update(29999,true,false,5000))
        assertTrue(deadline.update(30000,true,false,6000))
    }
    @Test fun pauseBackgroundSuppressionOrSelectorDoesNotConsumeBudget() {
        val deadline=LiteStartupDeadline(0)
        assertFalse(deadline.update(5000,true,false,0))
        assertFalse(deadline.update(5100,false,false,0))
        assertFalse(deadline.update(100000,false,false,0))
        assertFalse(deadline.update(100100,true,false,0))
        assertFalse(deadline.update(102099,true,false,0))
        assertTrue(deadline.update(102100,true,false,0))
    }
    @Test fun actualPlaybackDisablesGuardIncludingLaterRebuffer() {
        val deadline=LiteStartupDeadline(0)
        assertFalse(deadline.update(15000,true,true,0))
        assertTrue(deadline.started)
        assertFalse(deadline.update(90000,true,false,0))
    }
    @Test fun decreasingBufferOrUnknownBufferDoesNotPretendProgress() {
        val deadline=LiteStartupDeadline(0)
        assertFalse(deadline.update(1000,true,false,1000))
        assertFalse(deadline.update(7999,true,false,-1))
        assertTrue(deadline.update(8000,true,false,500))
    }
    @Test fun separateAttemptGetsFreshBudget() {
        assertTrue(LiteStartupDeadline(0).update(7000,true,false,0))
        assertFalse(LiteStartupDeadline(7000).update(7001,true,false,0))
    }
}
