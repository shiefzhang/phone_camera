package com.example.phonecamera;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ConnectionRegistry {
    private static final int MAX_HISTORY = 12;

    private final Map<String, ConnectionInfo> connections = new LinkedHashMap<>();
    private final List<ConnectionInfo> history = new ArrayList<>();
    private int nextId = 1;

    public synchronized String add(String protocol, String username, String address) {
        String id = protocol + "-" + nextId++;
        connections.put(id, new ConnectionInfo(protocol, username, address, System.currentTimeMillis()));
        return id;
    }

    public synchronized void remove(String id) {
        ConnectionInfo info = connections.remove(id);
        if (info == null) {
            return;
        }
        info.disconnectedAtMs = System.currentTimeMillis();
        history.add(0, info);
        while (history.size() > MAX_HISTORY) {
            history.remove(history.size() - 1);
        }
    }

    public synchronized List<ConnectionInfo> snapshot() {
        return new ArrayList<>(connections.values());
    }

    public synchronized List<ConnectionInfo> historySnapshot() {
        return new ArrayList<>(history);
    }

    public static class ConnectionInfo {
        public final String protocol;
        public final String username;
        public final String address;
        public final long connectedAtMs;
        public long disconnectedAtMs;

        ConnectionInfo(String protocol, String username, String address, long connectedAtMs) {
            this.protocol = protocol;
            this.username = username;
            this.address = address;
            this.connectedAtMs = connectedAtMs;
        }
    }
}
