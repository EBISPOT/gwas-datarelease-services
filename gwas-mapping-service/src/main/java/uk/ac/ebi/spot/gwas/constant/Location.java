package uk.ac.ebi.spot.gwas.constant;

public class Location {

    public static final String VARIATION = "https://rest.ensembl.org/variation/homo_sapiens";

    public static final String VARIATION_FILE = "variants.json";

    private Location(){
        // Never called
    }


    // [https://rest.ensembl.org/overlap/region/homo_sapiens/{chromosome}:{pos_up}-{position}?feature=gene]

}