package org.example.apispring.reco.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface SongRecordRepository extends JpaRepository<SongRecord, UUID> {
}
