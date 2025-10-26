package org.example.apispring.reco.domain;

import org.example.apispring.reco.domain.TagEnums.*;
import jakarta.validation.constraints.NotNull;

public record TrackConstraints(
        @NotNull MOOD mood,
        @NotNull GENRE genre,
        @NotNull ACTIVITY activity,
        @NotNull BRANCH branch,
        @NotNull TEMPO tempo
) { }
