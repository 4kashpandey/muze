package com.muze.web;

import com.muze.domain.model.Room;
import com.muze.service.RoomService;
import com.muze.web.dto.ActiveRoomSummaryDto;
import com.muze.web.dto.CreateRoomRequest;
import com.muze.web.dto.RoomResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/rooms")
@CrossOrigin(origins = "*")
public class RoomController {

    private final RoomService roomService;

    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createRoom(
            @Valid @RequestBody CreateRoomRequest request,
            Authentication authentication
    ) {
        String effectiveUsername = (authentication != null && authentication.isAuthenticated() && !"anonymousUser".equals(authentication.getPrincipal()))
                ? authentication.getName()
                : request.username();

        String userId = (request.userId() != null && !request.userId().isBlank())
                ? request.userId().trim()
                : "usr_" + UUID.randomUUID().toString().substring(0, 8);

        boolean isPublic = (request.isPublic() == null) || request.isPublic();

        Room room = roomService.createRoom(
                request.name(),
                userId,
                effectiveUsername,
                request.initialTrackId(),
                isPublic,
                request.passcode()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "roomId", room.getRoomId(),
                "name", room.getName(),
                "userId", userId,
                "username", effectiveUsername,
                "role", "HOST",
                "isPublic", room.isPublic(),
                "currentTrackId", room.getCurrentTrackId() != null ? room.getCurrentTrackId() : ""
        ));
    }

    @GetMapping("/active")
    public ResponseEntity<List<ActiveRoomSummaryDto>> listActiveRooms() {
        return ResponseEntity.ok(roomService.getActiveRooms());
    }

    @PostMapping("/{roomId}/verify")
    public ResponseEntity<Map<String, Object>> verifyPasscode(
            @PathVariable String roomId,
            @RequestBody Map<String, String> body
    ) {
        String code = body != null ? body.get("passcode") : "";
        boolean valid = roomService.verifyRoomAccess(roomId.trim().toUpperCase(), code);
        return ResponseEntity.ok(Map.of("valid", valid));
    }

    @GetMapping("/{roomId}")
    public ResponseEntity<RoomResponse> getRoom(@PathVariable String roomId) {
        return roomService.getRoom(roomId.trim().toUpperCase())
                .map(room -> ResponseEntity.ok(RoomResponse.fromDomain(room)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}