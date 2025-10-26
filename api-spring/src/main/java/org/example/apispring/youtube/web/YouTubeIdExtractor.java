package org.example.apispring.youtube.web;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 🎬 YouTube 공유 링크나 watch URL에서 videoId만 추출하는 유틸리티
 * 예시:
 *   https://youtu.be/ATK7gAaZTOM?si=abcd  → ATK7gAaZTOM
 *   https://www.youtube.com/watch?v=ATK7gAaZTOM  → ATK7gAaZTOM
 */
public final class YouTubeIdExtractor {
    private YouTubeIdExtractor() {}

    private static final Pattern[] PATTERNS = new Pattern[]{
            Pattern.compile("youtu\\.be/([A-Za-z0-9_-]{11})"),
            Pattern.compile("[?&]v=([A-Za-z0-9_-]{11})"),
            Pattern.compile("youtube\\.com/shorts/([A-Za-z0-9_-]{11})"),
            Pattern.compile("music\\.youtube\\.com/watch\\?.*?[&?]v=([A-Za-z0-9_-]{11})")
    };

    public static String extract(String url) {
        if (url == null) return null;
        for (Pattern p : PATTERNS) {
            Matcher m = p.matcher(url);
            if (m.find()) return m.group(1);
        }
        return null;
    }
}
