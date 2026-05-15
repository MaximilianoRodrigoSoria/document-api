package ar.com.laboratory.model.entity;

import ar.com.laboratory.model.enums.DocumentDomain;
import ar.com.laboratory.model.enums.DocumentTemplateStatus;
import ar.com.laboratory.model.enums.OutputType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

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
import java.util.UUID;

@Entity
@Table(name = "document_templates", schema = "data")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, unique = true, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "domain", nullable = false, length = 100)
    private DocumentDomain domain;

    @Column(name = "gcs_key", nullable = true, length = 1000)
    private String gcsKey;

    /**
     * Contenido HTML Mustache del template.
     * Sólo relevante cuando {@link #outputType} es {@link OutputType#PDF}.
     * Para templates DOCX este campo es {@code null} — el binario .docx se
     * referencia mediante {@link #gcsKey}.
     */
    @Column(name = "content", nullable = true, columnDefinition = "TEXT")
    private String content;

    /**
     * Tipo de documento de salida. Determina qué generador procesa este template.
     * Valor por defecto: {@link OutputType#PDF} (backward-compat con templates existentes).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "output_type", nullable = false, length = 20)
    private OutputType outputType = OutputType.PDF;

    @Column(name = "version", nullable = false, length = 50)
    private String version;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DocumentTemplateStatus status;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DocumentTemplate other)) return false;
        return this.id != null && this.id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
