package uk.ac.ebi.spot.gwas.data.copy.oracle.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.ac.ebi.spot.gwas.data.copy.model.NoteSubject;


/**
 * Created by xinhe on 06/04/2017.
 */

public interface NoteSubjectRepository extends JpaRepository<NoteSubject, Long> {

    NoteSubject findBySubjectIgnoreCase(String subject);
}
