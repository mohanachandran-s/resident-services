package io.mosip.resident.util;

import io.mosip.kernel.core.http.ResponseWrapper;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.resident.config.LoggerConfiguration;
import io.mosip.resident.constant.ApiName;
import io.mosip.resident.constant.ResidentConstants;
import io.mosip.resident.constant.ResidentErrorCode;
import io.mosip.resident.exception.ApisResourceAccessException;
import io.mosip.resident.exception.ResidentServiceCheckedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @author Kamesh Shekhar Prasad
 */

@Component
public class PartnersByPartnerType {

    private static final Logger logger = LoggerConfiguration.logConfig(PartnersByPartnerType.class);

    private static final String PAGE_NO = "pageNo";
    private static final String PAGE_SIZE = "pageSize";
    private static final String DATA = "data";
    private static final String TOTAL_RESULTS = "totalResults";

    /** Fallback page size used when the configured value is invalid (zero or negative). */
    private static final int DEFAULT_PAGE_SIZE = 10;

    /**
     * Number of records fetched per page from the partner manager v2 API.
     * Configurable via property; defaults to 10.
     */
    @Value("${resident.partner.list.page.size:10}")
    private int pageSize;

    @Autowired
    private ResidentServiceRestClient residentServiceRestClient;

    @SuppressWarnings("unchecked")
    public ResponseWrapper<?> getPartnersByPartnerType(Optional<String> partnerType, ApiName apiUrl)
            throws ResidentServiceCheckedException {
        logger.debug("GetPartnersByPartnerType::getPartnersByPartnerType()::entry");

        ResponseWrapper<Object> mergedResponseWrapper = new ResponseWrapper<>();
        List<Object> mergedData = new ArrayList<>();
        int pageNo = 0;
        int totalResults = 0;

        int effectivePageSize = pageSize;
        if (effectivePageSize <= 0) {
            logger.warn(String.format(
                    "Invalid resident.partner.list.page.size [%d]; falling back to default [%d]",
                    pageSize, DEFAULT_PAGE_SIZE));
            effectivePageSize = DEFAULT_PAGE_SIZE;
        }

        try {
            do {
                List<String> pathsegements = null;

                List<String> queryParamName = new ArrayList<>();
                List<Object> queryParamValue = new ArrayList<>();

                if (partnerType.isPresent()) {
                    queryParamName.add(ResidentConstants.PARTNER_TYPE);
                    queryParamValue.add(partnerType.get());
                }
                queryParamName.add(PAGE_SIZE);
                queryParamValue.add(effectivePageSize);
                queryParamName.add(PAGE_NO);
                queryParamValue.add(pageNo);

                ResponseWrapper<?> responseWrapper = (ResponseWrapper<?>) residentServiceRestClient.getApi(apiUrl,
                        pathsegements, queryParamName, queryParamValue, ResponseWrapper.class);

                if (responseWrapper.getErrors() != null && !responseWrapper.getErrors().isEmpty()) {
                    logger.error(responseWrapper.getErrors().get(0).toString());
                    throw new ResidentServiceCheckedException(responseWrapper.getErrors().get(0).getErrorCode(),
                            responseWrapper.getErrors().get(0).getMessage());
                }

                Map<String, Object> pageResponse = (Map<String, Object>) responseWrapper.getResponse();
                if (pageResponse == null) {
                    break;
                }

                Object totalResultsObj = pageResponse.get(TOTAL_RESULTS);
                if (totalResultsObj instanceof Number) {
                    totalResults = ((Number) totalResultsObj).intValue();
                }

                List<Object> pageData = (List<Object>) pageResponse.get(DATA);
                if (pageData == null || pageData.isEmpty()) {
                    // No more records to read; stop to avoid an infinite loop.
                    break;
                }
                mergedData.addAll(pageData);

                pageNo++;
            } while (mergedData.size() < totalResults);

        } catch (ApisResourceAccessException e) {
            logger.error("Error occured in accessing partners list %s", e.getMessage());
            throw new ResidentServiceCheckedException(ResidentErrorCode.API_RESOURCE_ACCESS_EXCEPTION.getErrorCode(),
                    ResidentErrorCode.API_RESOURCE_ACCESS_EXCEPTION.getErrorMessage(), e);
        }

        // Transform the merged v2 records into the v1 "partners" shape the UI expects.
        Map<String, Object> mergedResponse = new LinkedHashMap<>();
        mergedResponse.put(ResidentConstants.PARTNERS, toV1Partners(mergedData));
        mergedResponseWrapper.setResponse(mergedResponse);

        logger.debug("GetPartnersByPartnerType::getPartnersByPartnerType()::exit");
        return mergedResponseWrapper;
    }

    /**
     * Maps partner manager v2 records ({@code data} array) to the response consumed by
     * the UI and internal services. Every v2 field is kept as-is; only the three v1
     * aliases ({@code partnerID}, {@code organizationName}, {@code emailId}) are added
     * on top. The extra v2 keys are harmless to consumers, which read fields by name.
     * {@code contactNumber} and {@code address} are not provided by v2, so they are
     * added as {@code null} only when absent.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toV1Partners(List<Object> v2Partners) {
        List<Map<String, Object>> partners = new ArrayList<>();
        for (Object partner : v2Partners) {
            Map<String, Object> v2 = (Map<String, Object>) partner;
            Map<String, Object> v1 = new LinkedHashMap<>(v2);

            /*
             * Backward compatibility: the v2 partner manager API renamed these three
             * fields (partnerId, orgName, emailAddress). Existing consumers - the
             * resident UI dropdowns and the OrderCard/Credential services - still read
             * the original v1 names, so we expose them as aliases alongside the v2
             * fields. The v2 keys are intentionally left in place; extra keys are
             * ignored by consumers, which read fields by name.
             */
            v1.put(ResidentConstants.PMS_PARTNER_ID, v2.get(ResidentConstants.PMS_PARTNER_ID_V2));
            v1.put(ResidentConstants.ORGANIZATION_NAME, v2.get(ResidentConstants.PARTNER_ORG_NAME_V2));
            v1.put(ResidentConstants.PARTNER_EMAIL_ID, v2.get(ResidentConstants.PARTNER_EMAIL_ADDRESS_V2));

            // Present in the v1 contract but not returned by v2; putIfAbsent so a
            // future v2 value is never overwritten with null.
            v1.putIfAbsent(ResidentConstants.PARTNER_CONTACT_NUMBER, null);
            v1.putIfAbsent(ResidentConstants.PARTNER_ADDRESS, null);
            partners.add(v1);
        }
        return partners;
    }
}
