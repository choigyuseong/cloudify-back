package org.example.apispring.song.domain;

public final class TagEnums {
    private TagEnums() {}

    public static final String GENRE_UNKNOWN = "unknown";

    public enum MOOD { hype, happy, chill, dreamy, peaceful, sad }
    public enum GENRE { city_pop, ballad, acoustic, indie, lofi, pop, dance, rnb, edm }
    public enum ACTIVITY { party, workout, picnic, study, night_drive, sleep }
    public enum BRANCH { calm, uplift }
    public enum TEMPO { slow, mid, fast }
}