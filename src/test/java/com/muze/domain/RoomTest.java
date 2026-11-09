package com.muze.domain;

import com.muze.domain.model.Participant;
import com.muze.domain.model.ParticipantRole;
import com.muze.domain.model.QueuedTrack;
import com.muze.domain.model.Room;
import com.muze.domain.model.TrackMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RoomTest {

    @Test
    @DisplayName("Creator should be designated as HOST")
    void testCreatorIsHost() {
        Room room = new Room("RM123", "Chill Vibes", "usr_1", "Alex", "trk_01");

        assertEquals("RM123", room.getRoomId());
        assertEquals("usr_1", room.getHostUserId());
        assertTrue(room.hasPlaybackAuthority("usr_1"));

        Participant host = room.getParticipant("usr_1").orElseThrow();
        assertEquals(ParticipantRole.HOST, host.role());
    }

    @Test
    @DisplayName("Subsequent joiners should be designated as LISTENER")
    void testSecondJoinerIsListener() {
        Room room = new Room("RM123", "Chill Vibes", "usr_1", "Alex", "trk_01");
        Participant listener = room.addParticipant("usr_2", "Sam", false);

        assertEquals(ParticipantRole.LISTENER, listener.role());
        assertFalse(room.hasPlaybackAuthority("usr_2"));
    }

    @Test
    @DisplayName("Host disconnect promotes verified member even if joined after a guest")
    void testHostElectionPromotesVerifiedMemberOverEarlierGuest() {
        Room room = new Room("RM123", "Chill Vibes", "usr_1", "Alex", "trk_01");

        // 1. Guest joins first
        Participant guest = room.addParticipant("usr_guest", "Guest-9999", true);
        assertEquals(ParticipantRole.LISTENER, guest.role());
        assertTrue(guest.isGuest());

        // 2. Verified member joins second (later tenure than guest)
        Participant verifiedUser = room.addParticipant("usr_verified", "Jordan", false);
        assertEquals(ParticipantRole.LISTENER, verifiedUser.role());
        assertFalse(verifiedUser.isGuest());

        // 3. Host leaves
        Optional<Participant> newHost = room.removeParticipant("usr_1");

        assertTrue(newHost.isPresent(), "A new host should be elected");
        assertEquals("usr_verified", newHost.get().userId(), "Verified member Jordan must be promoted over earlier Guest");
        assertEquals(ParticipantRole.HOST, newHost.get().role());
        assertEquals("usr_verified", room.getHostUserId());
        assertTrue(room.hasPlaybackAuthority("usr_verified"));
        assertFalse(room.hasPlaybackAuthority("usr_guest"), "Guest must never gain playback authority");
    }

    @Test
    @DisplayName("Host disconnect when only guests remain should not elect guest as host")
    void testHostElectionWhenOnlyGuestsRemainDisbands() {
        Room room = new Room("RM123", "Chill Vibes", "usr_1", "Alex", "trk_01");

        // Only guests join
        room.addParticipant("usr_guest1", "Guest-1111", true);
        room.addParticipant("usr_guest2", "Guest-2222", true);

        // Host leaves
        Optional<Participant> newHost = room.removeParticipant("usr_1");

        assertTrue(newHost.isEmpty(), "Guests must not be elected as HOST");
        assertNull(room.getHostUserId(), "Host user ID must be null");
        assertFalse(room.hasPlaybackAuthority("usr_guest1"));
        assertFalse(room.hasPlaybackAuthority("usr_guest2"));
    }

    @Test
    @DisplayName("Queue operations adhere to strict FIFO ordering")
    void testQueueFifoOrdering() {
        Room room = new Room("RM123", "Chill Vibes", "usr_1", "Alex", "trk_01");

        TrackMetadata trackA = new TrackMetadata("trk_A", "Song A", "Artist A", 180000L, null, "Rock", "artA", "urlA");
        TrackMetadata trackB = new TrackMetadata("trk_B", "Song B", "Artist B", 200000L, null, "Pop", "artB", "urlB");
        TrackMetadata trackC = new TrackMetadata("trk_C", "Song C", "Artist C", 220000L, null, "Jazz", "artC", "urlC");

        QueuedTrack qA = room.enqueueTrack(trackA, "Alex");
        QueuedTrack qB = room.enqueueTrack(trackB, "Sam");
        QueuedTrack qC = room.enqueueTrack(trackC, "Jordan");

        assertEquals(3, room.getQueueSize());
        List<QueuedTrack> queue = room.getQueue();
        assertEquals("trk_A", queue.get(0).trackId());
        assertEquals("trk_B", queue.get(1).trackId());
        assertEquals("trk_C", queue.get(2).trackId());
        assertEquals("Alex", queue.get(0).addedByUsername());

        // Dequeue head (Song A)
        Optional<QueuedTrack> dequeued = room.dequeueNextTrack(System.currentTimeMillis());
        assertTrue(dequeued.isPresent());
        assertEquals("trk_A", dequeued.get().trackId());
        assertEquals("trk_A", room.getCurrentTrackId());
        assertEquals("Song A", room.getCurrentTrackTitle());
        assertEquals(2, room.getQueueSize());

        // Remaining queue should be [Song B, Song C]
        assertEquals("trk_B", room.getQueue().get(0).trackId());
        assertEquals("trk_C", room.getQueue().get(1).trackId());
    }

    @Test
    @DisplayName("Removing queued track in-between strictly preserves relative order of remaining items")
    void testRemoveQueuedTrackPreservesOrder() {
        Room room = new Room("RM123", "Chill Vibes", "usr_1", "Alex", "trk_01");

        TrackMetadata t1 = new TrackMetadata("t1", "Track 1", "Artist 1", 100000L, null, "LoFi", null, null);
        TrackMetadata t2 = new TrackMetadata("t2", "Track 2", "Artist 2", 100000L, null, "LoFi", null, null);
        TrackMetadata t3 = new TrackMetadata("t3", "Track 3", "Artist 3", 100000L, null, "LoFi", null, null);
        TrackMetadata t4 = new TrackMetadata("t4", "Track 4", "Artist 4", 100000L, null, "LoFi", null, null);

        QueuedTrack q1 = room.enqueueTrack(t1, "User1");
        QueuedTrack q2 = room.enqueueTrack(t2, "User2");
        QueuedTrack q3 = room.enqueueTrack(t3, "User3");
        QueuedTrack q4 = room.enqueueTrack(t4, "User4");

        // Remove track 2 in the middle
        boolean removed = room.removeQueuedTrack(q2.queueId());
        assertTrue(removed);
        assertEquals(3, room.getQueueSize());

        // Order MUST be [t1, t3, t4]
        List<QueuedTrack> remaining = room.getQueue();
        assertEquals("t1", remaining.get(0).trackId());
        assertEquals("t3", remaining.get(1).trackId());
        assertEquals("t4", remaining.get(2).trackId());

        // Remove non-existent queueId should safely return false
        assertFalse(room.removeQueuedTrack("non_existent_id"));
        assertEquals(3, room.getQueueSize());
    }

    @Test
    @DisplayName("Dequeuing from empty queue returns Optional.empty")
    void testDequeueFromEmptyQueue() {
        Room room = new Room("RM123", "Chill Vibes", "usr_1", "Alex", "trk_01");
        assertEquals(0, room.getQueueSize());

        Optional<QueuedTrack> dequeued = room.dequeueNextTrack(System.currentTimeMillis());
        assertTrue(dequeued.isEmpty());
    }
}
