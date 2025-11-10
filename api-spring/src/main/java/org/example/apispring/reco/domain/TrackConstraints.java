package org.example.apispring.reco.domain;

import org.example.apispring.reco.domain.TagEnums.*;

public record TrackConstraints(
        MOOD mood,
        GENRE genre,
        ACTIVITY activity,
        BRANCH branch,
        TEMPO tempo
) {
    /**
     * 🎯 CanonicalTagQuery의 태그 ID와 곡의 속성 매칭 여부 검사
     * 예: "MOOD.happy" → mood.name() == "HAPPY" → true
     */
    public boolean matches(String tagId) {
        if (tagId == null || tagId.isBlank()) return false;
        String id = tagId.toLowerCase();

        return id.equals("mood." + mood.name().toLowerCase())
                || id.equals("genre." + genre.name().toLowerCase())
                || id.equals("activity." + activity.name().toLowerCase())
                || id.equals("branch." + branch.name().toLowerCase())
                || id.equals("tempo." + tempo.name().toLowerCase());
    }
}
