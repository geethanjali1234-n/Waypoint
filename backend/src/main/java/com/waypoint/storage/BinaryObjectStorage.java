package com.waypoint.storage;

public interface BinaryObjectStorage {

    void store(String storageKey, byte[] content, String contentType);

    StoredObject get(String storageKey);

    void delete(String storageKey);

    boolean exists(String storageKey);

    record StoredObject(byte[] content, String contentType, long contentLength) {
    }
}
