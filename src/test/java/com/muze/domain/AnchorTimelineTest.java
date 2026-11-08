package com.muze.domain;

import com.muze.domain.model.AnchorTimeline;
import com.muze.domain.model.PlaybackStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AnchorTimelineTest {

    @Test
    @DisplayName("Should initialize with PAUSED status and zero position")
    void testInitialState() {
        AnchorTimeline timeline = AnchorTimeline.initial();
        assertEquals(PlaybackStatus.PAUSED, timeline.status());
        assertEquals(0L, timeline.anchorPositionMs());
        assertEquals(0L, timeline.anchorServerTimeMs());
        assertEquals(1.0, timeline.playbackRate());
    }

    @Test
    @DisplayName("Should correctly calculate position when PLAYING based on server clock elapsed time")
    void testPlayingPositionCalculation() {
        long tStart = 1000000L;
        AnchorTimeline timeline = AnchorTimeline.initial().transitionToPlay(tStart);

        assertEquals(PlaybackStatus.PLAYING, timeline.status());
        assertEquals(tStart, timeline.anchorServerTimeMs());

        // 5000 ms later
        long tNow = tStart + 5000L;
        long currentPos = timeline.calculateCurrentPosition(tNow);
        assertEquals(5000L, currentPos);
    }

    @Test
    @DisplayName("Should lock position upon PAUSE transition")
    void testPauseTransition() {
        long tStart = 1000000L;
        AnchorTimeline playing = AnchorTimeline.initial().transitionToPlay(tStart);

        // Pause 3200 ms later
        long tPause = tStart + 3200L;
        AnchorTimeline paused = playing.transitionToPause(tPause);

        assertEquals(PlaybackStatus.PAUSED, paused.status());
        assertEquals(3200L, paused.anchorPositionMs());
        assertEquals(0L, paused.anchorServerTimeMs());

        // Even 10 seconds later, position remains unchanged at 3200 ms
        assertEquals(3200L, paused.calculateCurrentPosition(tPause + 10000L));
    }

    @Test
    @DisplayName("Should update anchor position and reset timestamp on SEEK")
    void testSeekTransition() {
        long tStart = 1000000L;
        AnchorTimeline playing = AnchorTimeline.initial().transitionToPlay(tStart);

        long tSeek = tStart + 2000L;
        long seekTargetMs = 45000L; // 45 seconds into track
        AnchorTimeline sought = playing.transitionToSeek(seekTargetMs, tSeek);

        assertEquals(PlaybackStatus.PLAYING, sought.status());
        assertEquals(seekTargetMs, sought.anchorPositionMs());
        assertEquals(tSeek, sought.anchorServerTimeMs());

        // 1000 ms after seek
        assertEquals(46000L, sought.calculateCurrentPosition(tSeek + 1000L));
    }
}

