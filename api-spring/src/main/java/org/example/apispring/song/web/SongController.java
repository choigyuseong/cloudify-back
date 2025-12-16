package org.example.apispring.song.web;

import lombok.RequiredArgsConstructor;
import org.example.apispring.song.application.FillDbService;
import org.example.apispring.song.application.dto.YoutubeAudioFillResultDto;
import org.example.apispring.song.application.dto.YoutubeVideoThumbFillResultDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/songs")
@RequiredArgsConstructor
public class SongController {

    private final FillDbService fillDbService;

    @PostMapping("/fill/album-images/genius")
    public ResponseEntity<String> fillAlbumImagesFromGenius() {
        fillDbService.fillAlbumImagesFromGenius();
        return ResponseEntity.ok("GENIUS_ALBUM_IMAGES_FILLED");
    }

    @PostMapping("/fill/youtube-video-and-thumbnail")
    public ResponseEntity<YoutubeVideoThumbFillResultDto> fillYoutubeVideoIdAndThumbnail() {
        YoutubeVideoThumbFillResultDto result = fillDbService.fillYoutubeVideoIdAndThumbnail();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/fill/youtube-audio")
    public ResponseEntity<YoutubeAudioFillResultDto> fillYoutubeAudioId() {
        YoutubeAudioFillResultDto result = fillDbService.fillYoutubeAudioId();
        return ResponseEntity.ok(result);
    }
}
