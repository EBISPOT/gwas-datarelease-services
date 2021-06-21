package uk.ac.ebi.spot.gwas.service.mapping;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import uk.ac.ebi.spot.gwas.config.AppConfig;
import uk.ac.ebi.spot.gwas.dto.EnsemblData;
import uk.ac.ebi.spot.gwas.dto.EnsemblMappingResult;
import uk.ac.ebi.spot.gwas.dto.MappingDto;
import uk.ac.ebi.spot.gwas.dto.Variation;
import uk.ac.ebi.spot.gwas.model.Location;

import java.util.Collection;
import java.util.Optional;

@Slf4j
@Service
public class DataMappingService {

    @Autowired
    private Mapper mapper;

    @Autowired
    private AppConfig config;

    public EnsemblMappingResult mappingPipeline(EnsemblData ensemblData, String snpRsId, Collection<String> reportedGenes) {

        log.info("Mapping pipeline commenced");

        mapper.setEnsemblData(ensemblData);
        //log.info("{} looped", snpRsId);

        Variation variation = ensemblData.getVariations().get(snpRsId);
        EnsemblMappingResult mappingResult = new EnsemblMappingResult();
        mappingResult.setRsId(snpRsId);

        if (variation != null) {
            //log.info("{} found", variation.getName());
            if (Optional.ofNullable(variation.getError()).isPresent()) {
                mappingResult.addPipelineErrors(variation.getError());
            } else {

                String currentRsId = variation.getName();
                if (!currentRsId.equals(snpRsId)) {
                    mappingResult.setMerged(1);
                    mappingResult.setCurrentSnpId(currentRsId);
                }

                if (Optional.ofNullable(variation.getFailed()).isPresent()) {
                    mappingResult.addPipelineErrors(variation.getFailed());
                }

                // Mapping and genomic context calls
                Collection<Location> locations = mapper.getMappings(variation);
                mappingResult.setLocations(locations);

                // Add genomic context
                if (!locations.isEmpty()) {
                    // Functional class (most severe consequence). This implies there is at least one variant location.
                    if (Optional.ofNullable(variation.getMostSevereConsequence()).isPresent()) {
                        mappingResult.setFunctionalClass(variation.getMostSevereConsequence());
                    }

                    for (Location snpLocation : locations) {

                        // Overlapping genes
                        MappingDto ncbiOverlap = mapper.getOverlapGenes(snpLocation, config.getNcbiSource(), mappingResult);
                        ncbiOverlap.getGeneNames().forEach(mappingResult::addNcbiOverlappingGene);
                        ncbiOverlap.getGenomicContexts().forEach(mappingResult::addGenomicContext);

                        MappingDto ensemblOverlap = mapper.getOverlapGenes(snpLocation, config.getEnsemblSource(), mappingResult);
                        ensemblOverlap.getGeneNames().forEach(mappingResult::addEnsemblOverlappingGene);
                        ensemblOverlap.getGenomicContexts().forEach(mappingResult::addGenomicContext);

                        // Upstream Genes
                        mapper.getUpstreamGenes(snpLocation, config.getNcbiSource(), mappingResult).forEach(mappingResult::addGenomicContext);
                        mapper.getUpstreamGenes(snpLocation, config.getEnsemblSource(), mappingResult).forEach(mappingResult::addGenomicContext);

                        // Downstream Genes
                        mapper.getDownstreamGenes(snpLocation, config.getNcbiSource(), mappingResult).forEach(mappingResult::addGenomicContext);
                        mapper.getDownstreamGenes(snpLocation, config.getEnsemblSource(), mappingResult).forEach(mappingResult::addGenomicContext);
                    }
                }
            }
        } else {
            log.error("Variation call for SNP {} returned no result", snpRsId);
        }

        if (reportedGenes.isEmpty()) {
            String pipelineError = mapper.checkReportedGenes(reportedGenes, mappingResult.getLocations());
            mappingResult.addPipelineErrors(pipelineError);
        }

        return mappingResult;
    }

}
