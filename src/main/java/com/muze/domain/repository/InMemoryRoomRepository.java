package com.muze.domain.repository;

import com.muze.domain.model.Room;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance concurrent in-memory repository for rooms.
 * Can be swapped for a distributed Redis repository for horizontal clustering.
 */
@Repository
public class InMemoryRoomRepository implements RoomRepository {

    private final ConcurrentHashMap<String, Room> storage = new ConcurrentHashMap<>();

    @Override
    public Room save(Room room) {
        storage.put(room.getRoomId(), room);
        return room;
    }

    @Override
    public Optional<Room> findById(String roomId) {
        return Optional.ofNullable(storage.get(roomId));
    }

    @Override
    public Collection<Room> findAll() {
        return Collections.unmodifiableCollection(storage.values());
    }

    @Override
    public void deleteById(String roomId) {
        storage.remove(roomId);
    }

    @Override
    public boolean existsById(String roomId) {
        return storage.containsKey(roomId);
    }
}

