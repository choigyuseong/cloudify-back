package org.example.apispring.reco.domain;

public record SongRecord(
        String title,
        String artist,
        TrackConstraints constraints
) { }
