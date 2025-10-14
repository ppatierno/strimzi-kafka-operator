/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.resource;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Java representation of the JSON response from the /v1/directory-id endpoint of the KafkaAgent
 */
class DirectoryId {
    private final String directoryId;

    /**
     * Constructor
     * @param directoryId Directory ID
     */
    @JsonCreator
    public DirectoryId(@JsonProperty("directoryId") String directoryId) {
        this.directoryId = directoryId;
    }

    /**
     * The directory ID
     * @return Directory ID as string
     */
    public String directoryId() {
        return directoryId;
    }

    @Override
    public String toString() {
        return String.format("Directory ID: %s", directoryId);
    }
}
