package org.example.apispring.recommend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.apispring.recommend.domain.SongRecord;
import org.example.apispring.recommend.domain.SongRecordRepository;
import org.example.apispring.recommend.dto.SongResponse;
import org.example.apispring.recommend.service.youtube.YouTubeService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoIdFillService {

    private final SongRecordRepository repository;
    private final YouTubeService youTubeService;

    private static final int BATCH_SIZE = 50; // 한번에 처리할 곡 수

    /** 기존: SongResponse 리스트로 업데이트 */
    @Transactional
    public void fillMissingVideoIds(List<SongResponse> songs) {
        songs.stream()
                .filter(song -> song.videoId() == null || song.videoId().isBlank())
                .forEach(song -> {
                    try {
                        String videoId = youTubeService.fetchVideoIdBySearch(song.title(), song.artist());
                        if (videoId != null && !videoId.isBlank()) {
                            repository.updateVideoId(song.title(), song.artist(), videoId);
                            log.info("📌 VIDEO_ID 저장: {} - {} -> {}", song.title(), song.artist(), videoId);
                        }
                    } catch (Exception e) {
                        log.error("❌ 저장실패 {} - {}", song.title(), song.artist(), e.getMessage());
                    }
                });
    }

    /**
     * DB 전체 Video ID 자동 갱신용 메서드
     * - Controller에서 API 호출 시 사용
     * - 추천 리스트 없이, Video ID가 없는 모든 SongRecord를 배치로 처리
     */
    @Transactional
    public void fillVideoIds() {
        int page = 0;
        List<SongRecord> batch;
        do {
            batch = repository.findAllByVideoIdIsNull(PageRequest.of(page, BATCH_SIZE)).getContent();
            for (SongRecord song : batch) {
                try {
                    String videoId = youTubeService.fetchVideoIdBySearch(song.getTitle(), song.getArtist());
                    if (videoId != null && !videoId.isBlank()) {
                        song.setVideoId(videoId);
                        repository.save(song); // save로 업데이트
                        log.info("📌 VIDEO_ID 저장: {} - {} -> {}", song.getTitle(), song.getArtist(), videoId);
                    }
                } catch (Exception e) {
                    log.error("❌ 저장실패 {} - {}", song.getTitle(), song.getArtist(), e.getMessage());
                }
            }
            page++;
        } while (!batch.isEmpty());
    }
}
