package io.mosip.resident.util;

import java.net.URI;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import io.mosip.resident.constant.ApiName;
import io.mosip.resident.dto.PartnerResponseDto;
import io.mosip.resident.dto.ResponseWrapper;
import io.mosip.resident.exception.ApisResourceAccessException;

/**
 * Caches the single-partner lookup ({@code PARTNER_API_URL_V2 + "/" + issuer})
 * so repeated credential requests for the same issuer do not hit the partner
 * manager API every time.
 *
 * @author Kamesh Shekhar Prasad
 */
@Component
public class PartnerByIssuerCache {

    @Autowired
    private ResidentServiceRestClient residentServiceRestClient;

    @Autowired
    private Environment env;

    /*
     * unless: never cache a failed or empty partner lookup. Spring already skips
     * caching when the method throws, but a 200 response carrying errors or a null
     * body must not be cached either - otherwise a transient partner-manager outage
     * would be replayed for every subsequent reqCredential call (and a null
     * getResponse() would NPE downstream) until the scheduled eviction runs.
     */
    @SuppressWarnings("unchecked")
    @Cacheable(value = "partnerByIssuerCache", key = "#issuer",
            unless = "#result == null || #result.getResponse() == null "
                    + "|| (#result.getErrors() != null && !#result.getErrors().isEmpty())")
    public ResponseWrapper<PartnerResponseDto> getPartnerByIssuer(String issuer) throws ApisResourceAccessException {
        String partnerUrl = env.getProperty(ApiName.PARTNER_API_URL_V2.name()) + "/" + issuer;
        URI partnerUri = URI.create(partnerUrl);
        return (ResponseWrapper<PartnerResponseDto>) residentServiceRestClient.getApi(partnerUri, ResponseWrapper.class);
    }
}
