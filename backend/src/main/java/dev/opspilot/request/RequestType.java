package dev.opspilot.request;

public enum RequestType {
    ADD_SERVERS, REMOVE_SERVERS, OS_UPGRADE, CONFIG_CHANGE;

    /** Only scaling changes carry a server count. */
    public boolean needsServerCount() { return this == ADD_SERVERS || this == REMOVE_SERVERS; }

    /** Signed change in server count: +n for adds, -n for removals, 0 otherwise. */
    public int capacityDelta(int serverCount) {
        return switch (this) {
            case ADD_SERVERS -> serverCount;
            case REMOVE_SERVERS -> -serverCount;
            default -> 0;
        };
    }
}
