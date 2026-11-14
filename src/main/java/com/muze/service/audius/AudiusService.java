package com.muze.service.audius;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muze.domain.model.TrackMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service integrating with the Audius decentralized audio network.
 * Provides live search, trending music discovery, and stream URL resolution.
 * Control plane traffic is cached in-memory to prevent rate-limiting.
 * Audio bytes stream directly from decentralized IPFS/CDN nodes to client browsers.
 */
@Service
public class AudiusService {

    private static final Logger log = LoggerFactory.getLogger(AudiusService.class);
    private static final String AUDIUS_API_BASE = "https://api.audius.co/v1";
    private static final String APP_NAME = "muze";
    private static final Duration CACHE_TTL = Duration.ofMinutes(15);
    private static final int MAX_QUERY_CACHE_SIZE = 500;
    private static final int MAX_TRACK_CACHE_SIZE = 1000;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    // In-memory cache for query results and track lookups
    private final Map<String, CacheItem<List<TrackMetadata>>> queryCache = new ConcurrentHashMap<>();
    private final Map<String, CacheItem<TrackMetadata>> trackCache = new ConcurrentHashMap<>();

    private record CacheItem<T>(T data, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    public AudiusService() {
        this(new ObjectMapper());
    }

    public AudiusService(ObjectMapper objectMapper) {
        this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(4));
        requestFactory.setReadTimeout(Duration.ofSeconds(6));

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(AUDIUS_API_BASE)
                .build();
    }
    

    /**
     * Searches Audius for tracks matching the query string.
     */
    public List<TrackMetadata> searchTracks(String query, int limit) {
        if (query == null || query.trim().length() < 2) {
            return Collections.emptyList();
        }

        String normalizedKey = query.trim().toLowerCase() + ":" + limit;
        CacheItem<List<TrackMetadata>> cached = queryCache.get(normalizedKey);
        if (cached != null && !cached.isExpired()) {
            return cached.data();
        }

        try {
            String response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/tracks/search")
                            .queryParam("query", query.trim())
                            .queryParam("app_name", APP_NAME)
                            .queryParam("limit", Math.min(limit, 30))
                            .build())
                    .retrieve()
                    .body(String.class);

            List<TrackMetadata> results = parseTracksResponse(response);

            if (queryCache.size() > MAX_QUERY_CACHE_SIZE) {
                queryCache.clear();
            }
            queryCache.put(normalizedKey, new CacheItem<>(results, Instant.now().plus(CACHE_TTL)));

            return results;
        } catch (Exception e) {
            log.warn("Audius search failed for query [{}]: {}", query, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Fetches trending tracks from Audius for initial catalog view.
     */
    public List<TrackMetadata> getTrendingTracks(int limit) {
        String key = "trending:" + limit;
        CacheItem<List<TrackMetadata>> cached = queryCache.get(key);
        if (cached != null && !cached.isExpired()) {
            return cached.data();
        }

        try {
            String response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/tracks/trending")
                            .queryParam("app_name", APP_NAME)
                            .queryParam("limit", Math.min(limit, 30))
                            .build())
                    .retrieve()
                    .body(String.class);

            List<TrackMetadata> results = parseTracksResponse(response);

            queryCache.put(key, new CacheItem<>(results, Instant.now().plus(CACHE_TTL)));
            return results;
        } catch (Exception e) {
            log.warn("Audius trending fetch failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Resolves metadata for a specific track by its Audius ID.
     */
    public Optional<TrackMetadata> getTrackById(String trackId) {
        if (trackId == null || trackId.isBlank()) {
            return Optional.empty();
        }

        CacheItem<TrackMetadata> cached = trackCache.get(trackId);
        if (cached != null && !cached.isExpired()) {
            return Optional.of(cached.data());
        }

        try {
            String response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/tracks/{id}")
                            .queryParam("app_name", APP_NAME)
                            .build(trackId))
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            JsonNode data = root.path("data");
            if (data.isMissingNode() || data.isNull()) {
                return Optional.empty();
            }

            TrackMetadata track = parseSingleTrack(data);
            if (track != null) {
                if (trackCache.size() > MAX_TRACK_CACHE_SIZE) {
                    trackCache.clear();
                }
                trackCache.put(trackId, new CacheItem<>(track, Instant.now().plus(CACHE_TTL)));
                return Optional.of(track);
            }
        } catch (Exception e) {
            log.warn("Failed to fetch Audius track [{}]: {}", trackId, e.getMessage());
        }

        return Optional.empty();
    }

    private List<TrackMetadata> parseTracksResponse(String jsonResponse) {
        if (jsonResponse == null || jsonResponse.isBlank()) {
            return Collections.emptyList();
        }

        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            JsonNode dataArray = root.path("data");
            if (!dataArray.isArray()) {
                return Collections.emptyList();
            }

            List<TrackMetadata> tracks = new ArrayList<>();
            for (JsonNode node : dataArray) {
                TrackMetadata track = parseSingleTrack(node);
                if (track != null) {
                    tracks.add(track);
                }
            }
            return tracks;
        } catch (Exception e) {
            log.error("Failed to parse Audius JSON response", e);
            return Collections.emptyList();
        }
    }

    private TrackMetadata parseSingleTrack(JsonNode node) {
        try {
            String id = node.path("id").asText();
            String title = node.path("title").asText("Untitled");
            String artist = node.path("user").path("name").asText("Unknown Artist");
            long durationSec = node.path("duration").asLong(180L);
            long durationMs = durationSec * 1000L;

            String artworkUrl = null;
            JsonNode artworkNode = node.path("artwork");
            if (artworkNode.isObject()) {
                if (artworkNode.has("480x480")) {
                    artworkUrl = artworkNode.path("480x480").asText();
                } else if (artworkNode.has("150x150")) {
                    artworkUrl = artworkNode.path("150x150").asText();
                }
            }

            String streamUrl = AUDIUS_API_BASE + "/tracks/" + id + "/stream?app_name=" + APP_NAME;

            return new TrackMetadata(
                    id,
                    title,
                    artist,
                    durationMs,
                    null,
                    "Audius",
                    artworkUrl,
                    streamUrl
            );
        } catch (Exception e) {
            log.debug("Skipping malformed track node: {}", e.getMessage());
            return null;
        }
    }
}