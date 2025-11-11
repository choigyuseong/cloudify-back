package org.example.apispring.reco.service;

import com.opencsv.bean.CsvToBeanBuilder;
import lombok.RequiredArgsConstructor;
import org.example.apispring.reco.domain.SongRecord;
import org.example.apispring.reco.domain.SongRecordRepository;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.List;

/**
 * 🎵 CsvToDbLoader
 * - CSV → PostgreSQL 마이그레이션 자동 로더
 * - 앱 부팅 시 songs.csv를 읽어 DB에 초기 로드함
 * - 기존 데이터가 존재하면 중복 삽입을 방지
 */
@Service
@RequiredArgsConstructor
public class CsvToDbLoader {

    private final SongRecordRepository songRecordRepository;

    /**
     * ✅ 앱 부팅 시 자동 실행
     * PostgreSQL에 song_record 테이블이 비어 있으면 CSV 데이터를 로드함.
     * ⚠️ 현재는 마이그레이션 완료로 인해 자동 실행 중단됨.
     */
    // @PostConstruct   // ✅ 주석 처리 — 자동 실행 방지 (중복 삽입 예방)
    public void loadCsvToDatabase() {
        System.out.println("🚀 Starting CSV → PostgreSQL migration...");

        try {
            long existingCount = songRecordRepository.count();
            if (existingCount > 0) {
                System.out.println("ℹ️ Database already contains " + existingCount + " records. Skipping migration.");
                return;
            }

            InputStream is = getClass().getResourceAsStream("/data/songs.csv");
            if (is == null) {
                System.err.println("❌ songs.csv not found! Expected path: src/main/resources/data/songs.csv");
                return;
            }

            Reader reader = new InputStreamReader(is);
            List<SongRecord> songs = new CsvToBeanBuilder<SongRecord>(reader)
                    .withType(SongRecord.class)
                    .withIgnoreLeadingWhiteSpace(true)
                    .build()
                    .parse();

            if (songs.isEmpty()) {
                System.err.println("⚠️ songs.csv is empty — no data imported.");
                return;
            }

            songRecordRepository.saveAll(songs);
            System.out.println("✅ Migration success: " + songs.size() + " records loaded!");

        } catch (Exception e) {
            System.err.println("❌ Migration failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
