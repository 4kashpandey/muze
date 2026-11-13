package com.muze.web;

import com.muze.domain.model.TrackMetadata;
import com.muze.service.audius.AudiusService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/audius")
@CrossOrigin(origins = "*")
public class AudiusController {

    private final AudiusService audiusService;

    public AudiusController(AudiusService audiusService) {
        this.audiusService = audiusService;
    }

    @GetMapping("/search")
    public ResponseEntity<List<TrackMetadata>> searchTracks(
            @RequestParam("q") String query,
            @RequestParam(value = "limit", defaultValue = "15") int limit
    ) {
        // Passes query and the limit (defaults to 15 songs)
        List<TrackMetadata> results = audiusService.searchTracks(query, limit);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/trending")
    public ResponseEntity<List<TrackMetadata>> getTrending(
            @RequestParam(value = "limit", defaultValue = "15") int limit
    ) {
        List<TrackMetadata> results = audiusService.getTrendingTracks(limit);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/tracks/{trackId}")
    public ResponseEntity<TrackMetadata> getTrackDetails(@PathVariable String trackId) {
        // Uses getTrackById from your AudiusService
        return audiusService.getTrackById(trackId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}