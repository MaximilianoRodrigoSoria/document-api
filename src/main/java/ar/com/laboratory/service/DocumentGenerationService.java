package ar.com.laboratory.service;

import ar.com.laboratory.constants.AppConstants;
import ar.com.laboratory.constants.LogConstants;
import ar.com.laboratory.model.entity.RawData;
import ar.com.laboratory.model.enums.RawDataStatus;
import ar.com.laboratory.repository.RawDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentGenerationService {

    private final RawDataRepository rawDataRepository;

    @Transactional
    public int recoverStuckProcessing(int timeoutMinutes) {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(timeoutMinutes);
        List<RawData> stuck = rawDataRepository.findStuckProcessing(threshold);

        if (stuck.isEmpty()) {
            return 0;
        }

        stuck.forEach(r -> r.setStatus(RawDataStatus.PENDING));
        rawDataRepository.saveAll(stuck);

        log.warn("[{}] Recovered {} stuck PROCESSING record(s) → PENDING (threshold={}min)",
                LogConstants.LOG_RECOVERY_STUCK_FOUND, stuck.size(), timeoutMinutes);
        return stuck.size();
    }

    @Transactional
    public List<RawData> claimPendingRecords(int batchSize) {
        List<RawData> records = rawDataRepository.findPendingForProcessing(
                batchSize, AppConstants.MAX_RETRY_COUNT);

        if (!records.isEmpty()) {
            records.forEach(r -> r.setStatus(RawDataStatus.PROCESSING));
            rawDataRepository.saveAll(records);
            log.info("[{}] Claimed {} record(s) for processing",
                    LogConstants.LOG_SCHEDULER_START, records.size());
        }

        return records;
    }

    @Transactional
    public void markGenerated(RawData record, String documentUrl) {
        record.setStatus(RawDataStatus.GENERATED);
        record.setDocumentUrl(documentUrl);
        record.setProcessedAt(LocalDateTime.now());
        record.setErrorMessage(null);
        rawDataRepository.save(record);
    }

    @Transactional
    public void markPublished(RawData record) {
        record.setStatus(RawDataStatus.PUBLISHED);
        record.setPublishedAt(LocalDateTime.now());
        rawDataRepository.save(record);
    }

    @Transactional
    public void markError(RawData record, String message) {
        record.setRetryCount(record.getRetryCount() + 1);

        if (record.getRetryCount() >= AppConstants.MAX_RETRY_COUNT) {
            record.setStatus(RawDataStatus.FAILED);
            log.warn("[{}] Record permanently failed after {} retries: processId={}",
                    LogConstants.LOG_SCHEDULER_RECORD_ERROR,
                    record.getRetryCount(), record.getProcessId());
        } else {
            record.setStatus(RawDataStatus.ERROR);
            log.warn("[{}] Record marked for retry ({}/{}): processId={}",
                    LogConstants.LOG_SCHEDULER_RECORD_ERROR,
                    record.getRetryCount(), AppConstants.MAX_RETRY_COUNT,
                    record.getProcessId());
        }

        record.setErrorMessage(truncate(message));
        rawDataRepository.save(record);
    }

    private String truncate(String message) {
        if (message == null) return null;
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
