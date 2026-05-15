package ar.com.laboratory.model.entity;

import ar.com.laboratory.model.enums.RawDataStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "raw_data", schema = "data")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RawData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "process_id", updatable = false, nullable = false)
    private Long processId;

    @Column(name = "idempotency_id", nullable = false, unique = true, length = 255)
    private String idempotencyId;

    @Column(name = "template_name", nullable = false, length = 255)
    private String templateName;

    @Column(name = "type", length = 255)
    private String type;

    @Column(name = "data", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String data;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 45)
    @Builder.Default
    private RawDataStatus status = RawDataStatus.PENDING;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "document_url", length = 1000)
    private String documentUrl;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RawData other)) return false;
        return this.processId != null && this.processId.equals(other.processId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(processId);
    }
}
