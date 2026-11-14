package com.muze.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muze.domain.model.TrackMetadata;
import com.muze.service.audius.AudiusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AudiusServiceTest {

    private AudiusService audiusService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        audiusService = new AudiusService(objectMapper);
    }

    @Test
    @DisplayName("Empty or short query should return empty list without network call")
    void testEmptyQueryReturnsEmptyList() {
        List<TrackMetadata> results = audiusService.searchTracks("", 10);
        assertTrue(results.isEmpty());

        List<TrackMetadata> singleChar = audiusService.searchTracks("a", 10);
        assertTrue(singleChar.isEmpty());
    }

    @Test
    @DisplayName("Null track ID should return empty optional")
    void testFindTrackByIdNull() {
        assertTrue(audiusService.getTrackById(null).isEmpty());
        assertTrue(audiusService.getTrackById("").isEmpty());
    }
}

