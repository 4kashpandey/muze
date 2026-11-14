package com.muze.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muze.domain.model.TrackMetadata;
import com.muze.service.audius.AudiusService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AudiusControllerTest {

    @Test
    @DisplayName("AudiusController delegates cleanly to AudiusService")
    void testControllerDelegation() {
        AudiusService fakeService = new AudiusService(new ObjectMapper()) {
            @Override
            public List<TrackMetadata> searchTracks(String query, int limit) {
                if ("chill".equals(query)) {
                    return List.of(new TrackMetadata(
                            "audius:1", "Chill Song", "DJ Cool", 120000L, null, "Lofi",
                            "https://img.jpg", "https://stream.mp3"
                    ));
                }
                return Collections.emptyList();
            }

            @Override
            public List<TrackMetadata> getTrendingTracks(int limit) {
                return List.of(new TrackMetadata(
                        "audius:2", "Trending Song", "Pop Star", 180000L, null, "Pop",
                        null, "https://stream2.mp3"
                ));
            }
        };

        AudiusController controller = new AudiusController(fakeService);

        ResponseEntity<List<TrackMetadata>> searchResp = controller.searchTracks("chill", 10);
        assertEquals(HttpStatus.OK, searchResp.getStatusCode());
        assertNotNull(searchResp.getBody());
        assertEquals(1, searchResp.getBody().size());
        assertEquals("Chill Song", searchResp.getBody().get(0).title());

        ResponseEntity<List<TrackMetadata>> trendingResp = controller.getTrending(10);
        assertEquals(HttpStatus.OK, trendingResp.getStatusCode());
        assertNotNull(trendingResp.getBody());
        assertEquals(1, trendingResp.getBody().size());
        assertEquals("Trending Song", trendingResp.getBody().get(0).title());
    }
}