package com.ffproject.flagconsumer.flag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * The subset of the Flag Service's {@code GET /flags} response this consumer
 * cares about. Everything else ({@code id}, {@code createdAt}, ...) is ignored
 * by Jackson.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FlagSnapshot(String name, Boolean value, Long version, Instant updatedAt) {
}
