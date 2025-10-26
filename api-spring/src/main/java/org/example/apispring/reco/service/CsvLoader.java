package org.example.apispring.reco.service;

import com.opencsv.CSVReader;
import org.example.apispring.reco.domain.SongRecord;
import org.example.apispring.reco.domain.TagEnums.*;
import org.example.apispring.reco.domain.TrackConstraints;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStreamReader;
import java.util.*;

@Component
public class CsvLoader {

    public List<SongRecord> load(String songsPath, String constraintsPath) {
        try {
            var songs = readSongs(songsPath);
            var cons  = readConstraints(constraintsPath);
            return joinByTitleArtist(songs, cons);
        } catch (Exception e) {
            throw new RuntimeException("CSV load failed: " + e.getMessage(), e);
        }
    }

    private record SongRow(String title, String artist) {}
    private record ConsRow(String title, String artist,
                           String mood, String genre, String activity, String branch, String tempo) {}

    private List<SongRow> readSongs(String p) throws Exception {
        List<SongRow> rows = new ArrayList<>();
        var res = new ClassPathResource(p);
        try (var r = new CSVReader(new InputStreamReader(res.getInputStream()))) {
            String[] line; boolean header = false;
            while ((line = r.readNext()) != null) {
                if (!header) { header = true; continue; }
                rows.add(new SongRow(line[0].trim(), line[1].trim()));
            }
        }
        return rows;
    }

    private List<ConsRow> readConstraints(String p) throws Exception {
        List<ConsRow> rows = new ArrayList<>();
        var res = new ClassPathResource(p);
        try (var r = new CSVReader(new InputStreamReader(res.getInputStream()))) {
            String[] line; boolean header = false;
            while ((line = r.readNext()) != null) {
                if (!header) { header = true; continue; }
                rows.add(new ConsRow(
                        line[0].trim(), line[1].trim(),
                        line[2].trim(), line[3].trim(), line[4].trim(), line[5].trim(), line[6].trim()
                ));
            }
        }
        return rows;
    }

    private static String key(String title, String artist) {
        return (title == null ? "" : title).trim().toLowerCase()
                + "│"
                + (artist == null ? "" : artist).trim().toLowerCase();
    }

    private List<SongRecord> joinByTitleArtist(List<SongRow> songs, List<ConsRow> cons) {
        var consMap = new HashMap<String, ConsRow>();
        for (var c : cons) consMap.put(key(c.title, c.artist), c);

        List<SongRecord> out = new ArrayList<>();
        for (var s : songs) {
            var c = consMap.get(key(s.title, s.artist));
            if (c == null) continue;
            var tc = new TrackConstraints(
                    MOOD.valueOf(c.mood.toLowerCase()),
                    GENRE.valueOf(c.genre.toLowerCase()),
                    ACTIVITY.valueOf(c.activity.toLowerCase()),
                    BRANCH.valueOf(c.branch.toLowerCase()),
                    TEMPO.valueOf(c.tempo.toLowerCase())
            );
            out.add(new SongRecord(s.title, s.artist, tc));
        }
        return out;
    }
}
