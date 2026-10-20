package com.ffproject.flagconsumer.flag;

/**
 * A live flag as exposed by the read API. Tombstoned (deleted) flags never
 * become a {@code FlagView}.
 *
 * @param name    flag name
 * @param value   current boolean value
 * @param version version of the last event or snapshot entry applied
 * @param ts      timestamp carried by that event, or the snapshot's
 *                {@code updatedAt}; may be null for hand-crafted input
 */
public record FlagView(String name, boolean value, long version, String ts) {
}
