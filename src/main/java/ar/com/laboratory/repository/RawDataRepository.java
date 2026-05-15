package ar.com.laboratory.repository;

import ar.com.laboratory.model.entity.RawData;
import ar.com.laboratory.model.enums.RawDataStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface RawDataRepository extends JpaRepository<RawData, Long> {

    Optional<RawData> findByIdempotencyId(String idempotencyId);

    List<RawData> findByStatusIn(Collection<RawDataStatus> statuses);

    @Query("""
            SELECT r FROM RawData r
            WHERE r.status = ar.com.laboratory.model.enums.RawDataStatus.PROCESSING
              AND r.updatedAt < :threshold
            """)
    List<RawData> findStuckProcessing(@Param("threshold") LocalDateTime threshold);

    @Query(value = """
            SELECT * FROM data.raw_data
            WHERE status = 'PENDING'
               OR (status = 'ERROR' AND retry_count < :maxRetry)
            ORDER BY template_name ASC, created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<RawData> findPendingForProcessing(@Param("limit") int limit,
                                           @Param("maxRetry") int maxRetry);

    @Query("""
            SELECT COUNT(r) FROM RawData r
            WHERE r.status = ar.com.laboratory.model.enums.RawDataStatus.PENDING
               OR (r.status = ar.com.laboratory.model.enums.RawDataStatus.ERROR
                   AND r.retryCount < :maxRetry)
            """)
    long countPendingToProcess(@Param("maxRetry") int maxRetry);
}
