package com.muze.domain.repository;

import com.muze.domain.model.Room;
import java.util.Collection;
import java.util.Optional;

public interface RoomRepository {
    Room save(Room room);
    Optional<Room> findById(String roomId);
    Collection<Room> findAll();
    void deleteById(String roomId);
    boolean existsById(String roomId);
}

