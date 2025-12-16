package org.example.apispring.song.application;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.apispring.global.error.BusinessException;
import org.example.apispring.global.error.ErrorCode;
import org.example.apispring.song.application.dto.YoutubeAudioFillResultDto;
import org.example.apispring.song.application.dto.YoutubeVideoThumbFillResultDto;
import org.example.apispring.song.domain.Song;
import org.example.apispring.song.domain.SongRepository;
import org.example.apispring.song.web.GeniusClient;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FillDbService {

    private static final int GENIUS_BATCH_SIZE = 200;

    private final SongRepository songRepository;
    private final GeniusClient geniusClient;

    @Transactional
    public void fillAlbumImagesFromGenius() {
        final String rid = shortRid();
        log.info("[GeniusFill:{}] start batchSize={}", rid, GENIUS_BATCH_SIZE);

        int totalUpdated = 0;
        int totalTrash = 0;
        int totalSuccess = 0;
        int totalTransientSkip = 0;

        int round = 0;

        while (true) {
            round++;

            List<Song> batch = songRepository.findSongsWithoutAlbumImage(PageRequest.of(0, GENIUS_BATCH_SIZE));
            if (batch.isEmpty()) {
                log.info("[GeniusFill:{}] done. no more candidates. rounds={} updated={} success={} trash={} transientSkip={}",
                        rid, round, totalUpdated, totalSuccess, totalTrash, totalTransientSkip);
                break;
            }

            int updatedThisRound = 0;
            int trashThisRound = 0;
            int successThisRound = 0;
            int transientSkipThisRound = 0;

            List<Song> toSave = new ArrayList<>(batch.size());

            log.info("[GeniusFill:{}] round={} fetched={}", rid, round, batch.size());

            for (Song song : batch) {
                final String songId = song.getId();

                String artist = nullToEmpty(song.getArtist()).trim();
                String title = nullToEmpty(song.getTitle()).trim();
                String query = (artist + " " + title).trim();

                // (A) 진짜로 검색 불가능(데이터 결함) → trash 확정
                if (query.isBlank()) {
                    log.warn("[GeniusFill:{}] songId={} trash(reason=blank_query) artist='{}' title='{}'",
                            rid, songId, artist, title);
                    song.updateAlbumImageUrl("trash");
                    toSave.add(song);
                    updatedThisRound++; trashThisRound++;
                    continue;
                }

                ResponseEntity<String> res;
                try {
                    res = geniusClient.search(query);
                } catch (BusinessException be) {
                    if (be.errorCode() == ErrorCode.GENIUS_API_TOKEN_MISSING) {
                        log.error("[GeniusFill:{}] token missing -> abort", rid);
                        throw be;
                    }
                    // 네트워크/클라이언트 예외: transient로 보고 건드리지 않음(재시도)
                    log.warn("[GeniusFill:{}] songId={} transient_skip(reason=business_exception) code={} msg={} query='{}'",
                            rid, songId, be.errorCode().name(), be.getMessage(), query);
                    transientSkipThisRound++;
                    continue;
                } catch (Exception e) {
                    log.warn("[GeniusFill:{}] songId={} transient_skip(reason=exception) err={} msg={} query='{}'",
                            rid, songId, e.getClass().getSimpleName(), e.getMessage(), query);
                    transientSkipThisRound++;
                    continue;
                }

                if (res == null || res.getBody() == null) {
                    log.warn("[GeniusFill:{}] songId={} transient_skip(reason=null_response) status={}",
                            rid, songId, (res == null ? "null" : res.getStatusCode()));
                    transientSkipThisRound++;
                    continue;
                }

                int sc = res.getStatusCode().value();
                if (sc < 200 || sc >= 300) {
                    // 업스트림 상태 이상: trash 확정 X (재시도)
                    log.warn("[GeniusFill:{}] songId={} transient_skip(reason=non_2xx) status={} bodyPrefix={}",
                            rid, songId, sc, safePrefix(res.getBody(), 200));
                    transientSkipThisRound++;
                    continue;
                }

                JSONObject root;
                try {
                    root = new JSONObject(res.getBody());
                } catch (Exception e) {
                    log.warn("[GeniusFill:{}] songId={} transient_skip(reason=json_parse_fail) err={} bodyPrefix={}",
                            rid, songId, e.getClass().getSimpleName(), safePrefix(res.getBody(), 200));
                    transientSkipThisRound++;
                    continue;
                }

                JSONObject resp = root.optJSONObject("response");
                if (resp == null) {
                    log.warn("[GeniusFill:{}] songId={} transient_skip(reason=missing_response_node) bodyPrefix={}",
                            rid, songId, safePrefix(res.getBody(), 200));
                    transientSkipThisRound++;
                    continue;
                }

                JSONArray hits = resp.optJSONArray("hits");
                int hitCount = (hits == null ? 0 : hits.length());
                log.debug("[GeniusFill:{}] songId={} hits={}", rid, songId, hitCount);

                // (B) “진짜 결과 없음” → trash 확정
                if (hitCount == 0) {
                    log.info("[GeniusFill:{}] songId={} trash(reason=no_hits) query='{}'", rid, songId, query);
                    song.updateAlbumImageUrl("trash");
                    toSave.add(song);
                    updatedThisRound++; trashThisRound++;
                    continue;
                }

                // (C) 후보 선택 + 상세 로그
                GeniusPick pick = selectAlbumImage(hits, title, artist, rid, songId);

                if (pick.url() == null || pick.url().isBlank()) {
                    // “후보는 있지만 쓸 이미지가 없음” → 결과 없음으로 보고 trash 확정
                    log.info("[GeniusFill:{}] songId={} trash(reason={}) bestScore={} query='{}'",
                            rid, songId, pick.reason(), pick.bestScore(), query);
                    song.updateAlbumImageUrl("trash");
                    toSave.add(song);
                    updatedThisRound++; trashThisRound++;
                    continue;
                }

                log.info("[GeniusFill:{}] songId={} success url={} bestScore={} reason={}",
                        rid, songId, pick.url(), pick.bestScore(), pick.reason());

                song.updateAlbumImageUrl(pick.url());
                toSave.add(song);
                updatedThisRound++; successThisRound++;
            }

            if (!toSave.isEmpty()) {
                songRepository.saveAll(toSave);
            }

            totalUpdated += updatedThisRound;
            totalTrash += trashThisRound;
            totalSuccess += successThisRound;
            totalTransientSkip += transientSkipThisRound;

            log.info("[GeniusFill:{}] round={} saved={} (success={} trash={}) transientSkip={}",
                    rid, round, updatedThisRound, successThisRound, trashThisRound, transientSkipThisRound);

            // 무한루프 방지: 이번 라운드에 DB 변경이 0이면 종료(전부 transient 실패였다는 뜻)
            if (updatedThisRound == 0) {
                log.warn("[GeniusFill:{}] stop(no_progress). round={} transientSkip={} fetched={}",
                        rid, round, transientSkipThisRound, batch.size());
                break;
            }
        }
    }

    // ---------------------------------------
    // 후보 선택 + 로그
    // ---------------------------------------
    private GeniusPick selectAlbumImage(JSONArray hits, String title, String artist, String rid, String songId) {
        String wantTitle = norm(title);
        String wantArtist = norm(artist);

        JSONObject best = null;
        double bestScore = -1.0;
        String bestArt = null;

        int considered = 0;
        int skippedDefault = 0;
        int skippedArtistMismatch = 0;

        for (int i = 0; i < hits.length(); i++) {
            JSONObject hit = hits.optJSONObject(i);
            if (hit == null) continue;
            JSONObject result = hit.optJSONObject("result");
            if (result == null) continue;

            String art = pickArtUrl(result);
            if (art == null || isDefaultGeniusImage(art)) {
                skippedDefault++;
                continue;
            }

            String primaryArtist = norm(primaryArtistName(result));
            String rTitle = norm(result.optString("title", ""));
            String fullTitle = norm(result.optString("full_title", ""));

            double s = 0.0;

            // 아티스트 정합성 (너무 다르면 스킵)
            if (!wantArtist.isEmpty() && !primaryArtist.isEmpty()) {
                if (primaryArtist.equals(wantArtist)) s += 0.60;
                else if (primaryArtist.contains(wantArtist) || wantArtist.contains(primaryArtist)) s += 0.45;
                else {
                    skippedArtistMismatch++;
                    continue;
                }
            }

            if (!wantTitle.isEmpty()) {
                if (rTitle.equals(wantTitle)) s += 0.30;
                else if (rTitle.contains(wantTitle) || fullTitle.contains(wantTitle)) s += 0.20;
            }

            String noisy = rTitle + " " + fullTitle;
            if (noisy.matches(".*\\b(live|cover|remix|nightcore|sped up|lyrics)\\b.*")) s -= 0.25;

            considered++;

            if (log.isDebugEnabled()) {
                log.debug("[GeniusPick:{}] songId={} cand#{} score={} art={} pa='{}' title='{}'",
                        rid, songId, i, s, art, primaryArtist, rTitle);
            }

            if (s > bestScore) {
                bestScore = s;
                best = result;
                bestArt = art;
            }
        }

        if (bestArt != null && !bestArt.isBlank()) {
            log.debug("[GeniusPick:{}] songId={} selected score={} considered={} skipDefault={} skipArtistMismatch={}",
                    rid, songId, bestScore, considered, skippedDefault, skippedArtistMismatch);
            return new GeniusPick(bestArt, bestScore, "SELECTED_BEST");
        }

        // fallback: 점수 threshold(0.2) 이상만
        for (int i = 0; i < hits.length(); i++) {
            JSONObject hit = hits.optJSONObject(i);
            if (hit == null) continue;
            JSONObject result = hit.optJSONObject("result");
            if (result == null) continue;

            String art = pickArtUrl(result);
            if (art == null || isDefaultGeniusImage(art)) continue;

            String primaryArtist = norm(primaryArtistName(result));
            String rTitle = norm(result.optString("title", ""));
            String fullTitle = norm(result.optString("full_title", ""));

            double s = 0.0;
            if (!wantArtist.isEmpty() && !primaryArtist.isEmpty()) {
                if (primaryArtist.equals(wantArtist)) s += 0.60;
                else if (primaryArtist.contains(wantArtist) || wantArtist.contains(primaryArtist)) s += 0.45;
            }
            if (!wantTitle.isEmpty()) {
                if (rTitle.equals(wantTitle)) s += 0.30;
                else if (rTitle.contains(wantTitle) || fullTitle.contains(wantTitle)) s += 0.20;
            }

            if (s >= 0.2) {
                log.debug("[GeniusPick:{}] songId={} fallback_selected score={} art={}", rid, songId, s, art);
                return new GeniusPick(art, s, "SELECTED_FALLBACK");
            }
        }

        // 후보는 있는데 usable 이미지가 없음
        log.debug("[GeniusPick:{}] songId={} no_usable_art hits={} considered={} skipDefault={} skipArtistMismatch={}",
                rid, songId, hits.length(), considered, skippedDefault, skippedArtistMismatch);
        return new GeniusPick(null, bestScore, "NO_USABLE_ART");
    }

    private record GeniusPick(String url, double bestScore, String reason) {}

    // ---------------------------------------
    // 유틸
    // ---------------------------------------
    private static String shortRid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String safePrefix(String s, int n) {
        if (s == null) return "null";
        return s.length() <= n ? s : s.substring(0, n);
    }

    private static String nullToEmpty(String s) {
        return (s == null) ? "" : s;
    }

    private String pickArtUrl(JSONObject result) {
        if (result == null) return null;
        String[] keys = {"song_art_image_url", "song_art_image_thumbnail_url", "header_image_url"};
        for (String k : keys) {
            Object raw = result.opt(k);
            if (raw == null || raw == JSONObject.NULL) continue;
            String v = String.valueOf(raw);
            if (v.isBlank() || "null".equalsIgnoreCase(v)) continue;
            if (isHttp(v)) return v;
        }
        return null;
    }

    private String primaryArtistName(JSONObject result) {
        JSONObject pa = (result == null) ? null : result.optJSONObject("primary_artist");
        if (pa == null) return "";
        Object raw = pa.opt("name");
        if (raw == null || raw == JSONObject.NULL) return "";
        String name = String.valueOf(raw);
        return "null".equalsIgnoreCase(name) ? "" : name;
    }

    private boolean isDefaultGeniusImage(String url) {
        if (url == null) return true;
        return url.contains("/images/default") || url.contains("/default_thumb");
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
}
