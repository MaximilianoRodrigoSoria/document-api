package ar.com.laboratory.repository;

import ar.com.laboratory.model.entity.DocumentTemplate;
import ar.com.laboratory.model.enums.DocumentTemplateStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentTemplateRepository extends JpaRepository<DocumentTemplate, UUID> {

    Optional<DocumentTemplate> findByNameAndStatus(String name, DocumentTemplateStatus status);

    Optional<DocumentTemplate> findByName(String name);

    List<DocumentTemplate> findAllByOrderByNameAsc();
}
