package com.ge.research.semtk.services.sparql;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.ge.research.semtk.edc.SemtkEndpointProperties;

@Configuration
@ConfigurationProperties(prefix="results.edc.services", ignoreUnknownFields = true)
public class SparqlQuerySemtkEndpointProperties extends SemtkEndpointProperties {

}