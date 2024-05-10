package uk.ac.ebi.spot.gwas.rabbitmq.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.ac.ebi.spot.gwas.model.PublicationExtension;


public interface PublicationExtensionRepository extends JpaRepository<PublicationExtension, Long> {

}
