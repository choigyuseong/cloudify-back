package org.example.apispring.song.application;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.apispring.song.web.GeniusClient;
import org.example.apispring.global.error.BusinessException;
import org.example.apispring.global.error.ErrorCode;
import org.example.apispring.song.domain.Song;
import org.example.apispring.song.domain.SongRepository;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class FillDbService {

    private static final int GENIUS_BATCH_SIZE = 200;
    private static final int YOUTUBE_BATCH_SIZE = 50;

    private final SongRepository songRepository;
    private final GeniusClient geniusClient;
    private final YoutubeVideoIdSearchService youtubeVideoIdSearchService;

    @Transactional
    public void fillAlbumImagesFromGenius() {
        while (true) {
            List<Song> batch = songRepository.findSongsWithoutAlbumImage(
                    PageRequest.of(0, GENIUS_BATCH_SIZE)
            );
            if (batch.isEmpty()) {
                break;
            }

            for (Song song : batch) {
                try {
                    String query = (song.getArtist() + " " + song.getTitle()).trim();
                    if (query.isEmpty()) {
                        song.updateAlbumImageUrl("trash");
                        songRepository.save(song);
                        continue;
                    }

                    ResponseEntity<String> res = geniusClient.search(query);
                    if (!res.getStatusCode().is2xxSuccessful() || res.getBody() == null) {
                        continue;
                    }

                    JSONObject root = new JSONObject(res.getBody());
                    JSONObject resp = root.optJSONObject("response");
                    if (resp == null) {
                        song.updateAlbumImageUrl("trash");
                        songRepository.save(song);
                        continue;
                    }

                    JSONArray hits = resp.optJSONArray("hits");
                    if (hits == null || hits.isEmpty()) {
                        song.updateAlbumImageUrl("trash");
                        songRepository.save(song);
                        continue;
                    }

                    String artUrl = selectAlbumImageUrl(hits);
                    if (artUrl == null || artUrl.isBlank()) {
                        song.updateAlbumImageUrl("trash");
                    } else {
                        song.updateAlbumImageUrl(artUrl);
                    }
                    songRepository.save(song);

                } catch (BusinessException be) {
                    if (be.errorCode() == ErrorCode.GENIUS_API_TOKEN_MISSING) {
                        throw be;
                    }
                } catch (Exception e) {
                }
            }
        }
    }

    @Transactional
    public void fillYoutubeMetadata() {
        while (true) {
            List<Song> batch = songRepository.findSongsWithMissingYoutubeMeta(
                    PageRequest.of(0, YOUTUBE_BATCH_SIZE)
            );
            if (batch.isEmpty()) {
                break;
            }

            for (Song song : batch) {
                try {
                    boolean videoMissing = isBlank(song.getVideoId());
                    boolean thumbMissing = isBlank(song.getThumbnailImageUrl());

                    if (!videoMissing && !thumbMissing) {
                        continue;
                    }

                    if (videoMissing) {
                        String title = song.getTitle();
                        String artist = song.getArtist();
                        if (title == null || artist == null) {
                            continue;
                        }

                        String videoId = youtubeVideoIdSearchService.findVideoId(title, artist);
                        if (isBlank(videoId)) {
                            continue;
                        }

                        song.updateVideoId(videoId);

                        if (thumbMissing) {
                            song.updateThumbnailImageUrl(buildYoutubeThumbnailUrl(videoId));
                        }
                    } else {
                        String videoId = song.getVideoId();
                        if (!isBlank(videoId) && thumbMissing) {
                            song.updateThumbnailImageUrl(buildYoutubeThumbnailUrl(videoId));
                        }
                    }

                    songRepository.save(song);

                } catch (BusinessException be) {
                    if (be.errorCode() == ErrorCode.YOUTUBE_API_KEY_MISSING) {
                        throw be;
                    }
                } catch (Exception e) {
                }
            }
        }
    }

    private String selectAlbumImageUrl(JSONArray hits) {
        JSONObject best = null;
        double bestScore = -1.0;

        for (int i = 0; i < hits.length(); i++) {
            JSONObject hit = hits.optJSONObject(i);
            if (hit == null) continue;
            JSONObject result = hit.optJSONObject("result");
            if (result == null) continue;

            String art = pickArtUrl(result);
            if (art == null) continue;

            String primaryArtist = norm(primaryArtistName(result));
            String rTitle = norm(result.optString("title", ""));
            String fullTitle = norm(result.optString("full_title", ""));

            double s = 0.0;

            if (!primaryArtist.isEmpty()) {
                s += 0.6;
            }

            if (!rTitle.isEmpty()) {
                s += 0.3;
            } else if (!fullTitle.isEmpty()) {
                s += 0.2;
            }

            String noisy = rTitle + " " + fullTitle;
            if (noisy.matches(".*\\b(live|cover|remix|nightcore|sped up|lyrics)\\b.*")) {
                s -= 0.25;
            }

            String uploader = norm(result.optJSONObject("primary_artist").optString("name", ""));
            if (uploader.contains("genius")) {
                s += 0.15;
            }

            if (s > bestScore) {
                bestScore = s;
                best = result;
            }
        }

        if (best != null) {
            String art = pickArtUrl(best);
            if (art != null && !art.isBlank()) {
                return art;
            }
        }

        for (int i = 0; i < hits.length(); i++) {
            JSONObject hit = hits.optJSONObject(i);
            if (hit == null) continue;
            JSONObject result = hit.optJSONObject("result");
            String art = pickArtUrl(result);
            if (art != null && !art.isBlank()) {
                return art;
            }
        }

        return null;
    }

    private String pickArtUrl(JSONObject result) {
        if (result == null) return null;
        String[] keys = new String[]{"song_art_image_url", "song_art_image_thumbnail_url", "header_image_url"};
        for (String k : keys) {
            String v = result.optString(k, null);
            if (isHttp(v)) return v;
        }
        return null;
    }

    private String primaryArtistName(JSONObject result) {
        try {
            JSONObject pa = result.optJSONObject("primary_artist");
            if (pa != null) return pa.optString("name", "");
        } catch (Exception ignored) {
        }
        return "";
    }

    private boolean isHttp(String s) {
        return s != null && (s.startsWith("http://") || s.startsWith("https://"));
    }

    private String norm(String s) {
        if (s == null) return "";
        String x = s.toLowerCase(Locale.ROOT);
        x = Normalizer.normalize(x, Normalizer.Form.NFKC);
        x = x.replaceAll("\\(.*?\\)|\\[.*?\\]|\\{.*?\\}", " ");
        x = x.replaceAll("\\b(feat\\.|ft\\.|with)\\b.*", " ");
        x = x.replaceAll("[^0-9a-zA-Z가-힣ㄱ-ㅎㅏ-ㅣぁ-ゔァ-ヴー一-龯々〆〤\\s']", " ");
        x = x.replaceAll("\\s+", " ").trim();
        return x;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String buildYoutubeThumbnailUrl(String videoId) {
        if (videoId == null || videoId.isBlank()) {
            return null;
        }
        return "https://img.youtube.com/vi/" + videoId + "/hqdefault.jpg";
    }
}

