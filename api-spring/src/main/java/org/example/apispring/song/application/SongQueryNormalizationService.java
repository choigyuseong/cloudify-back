package org.example.apispring.song.application;

import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Locale;

@Service
public class SongQueryNormalizationService {

    public String cleanTitle(String title) {
        if (title == null) return "";

        String t = title;

        t = stripBracketContent(t);
        t = stripDashSuffix(t);
        t = stripFeatProdSuffix(t);
        t = normalizeWhitespace(t);

        return t;
    }

    public String normalizeForMatch(String s) {
        if (s == null) return "";
        String x = s.toLowerCase(Locale.ROOT);
        x = Normalizer.normalize(x, Normalizer.Form.NFKC);
        x = x.replaceAll("\\(.*?\\)|\\[.*?\\]|\\{.*?\\}", " ");
        x = x.replaceAll("\\b(feat\\.|ft\\.|with)\\b.*", " ");
        x = x.replaceAll("[^0-9a-zA-Z가-힣ㄱ-ㅎㅏ-ㅣぁ-ゔァ-ヴー一-龯々〆〤\\s']", " ");
        x = x.replaceAll("\\s+", " ").trim();
        return x;
    }

    private String stripBracketContent(String s) {
        String x = s;
        x = x.replaceAll("\\s*\\(.*?\\)\\s*", " ");
        x = x.replaceAll("\\s*\\[.*?\\]\\s*", " ");
        x = x.replaceAll("\\s*\\{.*?\\}\\s*", " ");
        return x;
    }

    private String stripDashSuffix(String s) {
        return s.replaceAll("\\s+[-–—]\\s+.*$", "");
    }

    private String stripFeatProdSuffix(String s) {
        return s.replaceAll("(?i)\\b(feat\\.|ft\\.|featuring|with|prod\\.|produced by)\\b.*$", "");
    }

    private String normalizeWhitespace(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }
}
