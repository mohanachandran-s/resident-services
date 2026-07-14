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

    @SuppressWarnings("unchecked")
    @Cacheable(value = "partnerByIssuerCache", key = "#issuer")
    public ResponseWrapper<PartnerResponseDto> getPartnerByIssuer(String issuer) throws ApisResourceAccessException {
        String partnerUrl = env.getProperty(ApiName.PARTNER_API_URL_V2.name()) + "/" + issuer;
        URI partnerUri = URI.create(partnerUrl);
        return (ResponseWrapper<PartnerResponseDto>) residentServiceRestClient.getApi(partnerUri, ResponseWrapper.class);
    }
}
