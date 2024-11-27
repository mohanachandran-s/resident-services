package io.mosip.resident.util;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.resident.config.LoggerConfiguration;
import io.mosip.resident.constant.IdType;
import io.mosip.resident.constant.LoggerFileConstant;
import io.mosip.resident.constant.ResidentConstants;
import io.mosip.resident.constant.ResidentErrorCode;
import io.mosip.resident.dto.IdResponseDTO1;
import io.mosip.resident.dto.IdentityDTO;
import io.mosip.resident.exception.ApisResourceAccessException;
import io.mosip.resident.exception.ResidentServiceCheckedException;
import io.mosip.resident.exception.ResidentServiceException;
import io.mosip.resident.handler.service.ResidentConfigService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.mosip.resident.constant.ResidentConstants.IDENTITY;

/**
 * @author Kamesh Shekhar Prasad
 */

@Component
public class IdentityUtil {

	private static final int SIZE = 1;
	private final Logger logger = LoggerConfiguration.logConfig(IdentityUtil.class);

	@Autowired
	private Environment env;

	@Autowired
	private CachedIdentityDataUtil cachedIdentityDataUtil;

	@Autowired
	private AccessTokenUtility accessTokenUtility;

	@Autowired
	private ResidentConfigService residentConfigService;

	@Autowired
	private MaskDataUtility maskDataUtility;

	private static final String EMAIL = "email";
	private static final String PHONE = "phone";
	private static final String DATE_OF_BIRTH = "dob";
	private static final String IMAGE = "mosip.resident.photo.token.claim-photo";
	private static final String PHOTO_ATTRIB_PROP = "mosip.resident.photo.attribute.name";
	private static final String PERPETUAL_VID = "perpetualVID";

	@Autowired
	private AvailableClaimValueUtility availableClaimValueUtility;

	@Autowired
	private Utility utility;

	@Value("${resident.dateofbirth.pattern}")
	private String dateFormat;

	@Autowired
	private ClaimValueUtility claimValueUtility;

	@Autowired
	private PerpetualVidUtil perpetualVidUtil;


	private List<String> nameValueList;

	public Map<String, Object> getIdentityAttributes(String id, String schemaType) throws ResidentServiceCheckedException, IOException {
		return getIdentityAttributes(id, schemaType, List.of(
				Objects.requireNonNull(env.getProperty(ResidentConstants.ADDITIONAL_ATTRIBUTE_TO_FETCH))
				.split(ResidentConstants.COMMA)));
	}

	public Map<String, Object> getIdentityAttributes(String id, String schemaType,
			List<String> additionalAttributes) throws ResidentServiceCheckedException {
		logger.debug("IdentityServiceImpl::getIdentityAttributes()::entry");
		try {
			IdResponseDTO1 idResponseDTO1;
			if(Utility.isSecureSession()){
				idResponseDTO1 = (IdResponseDTO1) cachedIdentityDataUtil.getCachedIdentityData(id, accessTokenUtility.getAccessToken(), IdResponseDTO1.class);
			} else {
				idResponseDTO1 = (IdResponseDTO1) cachedIdentityDataUtil.getIdentityData(id, IdResponseDTO1.class);
			}
			if(idResponseDTO1.getErrors() != null && idResponseDTO1.getErrors().size() > 0) {
				throw new ResidentServiceCheckedException(ResidentErrorCode.API_RESOURCE_ACCESS_EXCEPTION.getErrorCode(),
						idResponseDTO1.getErrors().get(0).getErrorCode() + " --> " + idResponseDTO1.getErrors().get(0).getMessage());
			}
			Map<String,Object> identity = (Map<String, Object>) idResponseDTO1.getResponse().getIdentity();
			List<String> finalFilter = new ArrayList<>();
			if(schemaType != null) {
				List<String> filterBySchema = residentConfigService.getUiSchemaFilteredInputAttributes(schemaType);
				finalFilter.addAll(filterBySchema);
			}
			if(additionalAttributes != null && additionalAttributes.size()>0){
				finalFilter.addAll(additionalAttributes);
			}
			Map<String, Object> response = finalFilter.stream()
					.peek(a -> {
						if(a.equals(PERPETUAL_VID) || a.equals(ResidentConstants.MASK_PERPETUAL_VID)
								&& !identity.containsKey(PERPETUAL_VID)) {
							Optional<String> perpVid= Optional.empty();
							try {
								perpVid = perpetualVidUtil.getPerpatualVid((String) identity.get(IdType.UIN.name()));
							} catch (ResidentServiceCheckedException | ApisResourceAccessException e) {
								throw new ResidentServiceException(ResidentErrorCode.API_RESOURCE_ACCESS_EXCEPTION.getErrorCode(),
										ResidentErrorCode.API_RESOURCE_ACCESS_EXCEPTION.getErrorMessage(), e);
							}
							if(perpVid.isPresent()) {
								String vid = perpVid.get();
								identity.put(PERPETUAL_VID, vid);
							}
						}
					})
					.peek(a -> {
						if(a.equals(env.getProperty(PHOTO_ATTRIB_PROP))) {
							String photo;
							try {
								if (Utility.isSecureSession()) {
									photo = availableClaimValueUtility.getAvailableClaimValue(env.getProperty(IMAGE));
								} else {
									photo = null;
								}
							} catch (ApisResourceAccessException e) {
								logger.error("Error occured in accessing picture from claims %s", e.getMessage());
								throw new ResidentServiceException(ResidentErrorCode.API_RESOURCE_ACCESS_EXCEPTION.getErrorCode(),
										ResidentErrorCode.API_RESOURCE_ACCESS_EXCEPTION.getErrorMessage(), e);
							}
							if(photo != null) {
								identity.put(env.getProperty(PHOTO_ATTRIB_PROP), photo);
							}
						}
					})
					.peek(attr -> {
						if(attr.contains(ResidentConstants.MASK_PREFIX)) {
							String attributeName = attr.replace(ResidentConstants.MASK_PREFIX, "");
							if(identity.containsKey(attributeName)) {
								identity.put(attr, maskDataUtility.convertToMaskData((String) identity.get(attributeName)));
							}
						}
					})
					.filter(attrib -> identity.containsKey(attrib))
					.collect(Collectors.toMap(Function.identity(), identity::get,(m1, m2) -> m1, () -> new LinkedHashMap<String, Object>()));
			response.put(IDENTITY, identity);
			logger.debug("IdentityServiceImpl::getIdentityAttributes()::exit");
			return response;
		} catch (ApisResourceAccessException e) {
			logger.error("Error occured in accessing identity data %s", e.getMessage());
			throw new ResidentServiceCheckedException(ResidentErrorCode.API_RESOURCE_ACCESS_EXCEPTION.getErrorCode(),
					ResidentErrorCode.API_RESOURCE_ACCESS_EXCEPTION.getErrorMessage(), e);
		}
	}

	public IdentityDTO getIdentity(String id) throws ResidentServiceCheckedException {
			return getIdentity(id, false, null);
	}

	public IdentityDTO getIdentity(String id, boolean fetchFace, String langCode) throws ResidentServiceCheckedException {
		logger.debug("IdentityServiceImpl::getIdentity()::entry");
		IdentityDTO identityDTO = new IdentityDTO();
		try {
			Map<String, Object> identity =	getIdentityAttributes(id, null);
			/**
			 * It is assumed that in the UI schema the UIN is added.
			 */
			identityDTO.setUIN(utility.getMappingValue(identity, IdType.UIN.name()));
			identityDTO.setEmail(utility.getMappingValue(identity, EMAIL));
			identityDTO.setPhone(utility.getMappingValue(identity, PHONE));
			String dateOfBirth = utility.getMappingValue(identity, DATE_OF_BIRTH);
			if(dateOfBirth != null && !dateOfBirth.isEmpty()) {
				identityDTO.setDateOfBirth(dateOfBirth);
				DateTimeFormatter formatter=DateTimeFormatter.ofPattern(dateFormat);
				LocalDate localDate=LocalDate.parse(dateOfBirth, formatter);
				identityDTO.setYearOfBirth(Integer.toString(localDate.getYear()));
			}
            identityDTO.setFullName(getFullName(identity, langCode));
			identityDTO.putAll((Map<? extends String, ? extends Object>) identity.get(IDENTITY));

			if(fetchFace) {
				identity.put(env.getProperty(ResidentConstants.PHOTO_ATTRIBUTE_NAME), claimValueUtility.getClaimValue(env.getProperty(IMAGE)));
				identity.remove("individualBiometrics");
			}

		} catch (IOException e) {
			logger.error("Error occured in accessing identity data %s", e.getMessage());
			throw new ResidentServiceCheckedException(ResidentErrorCode.IO_EXCEPTION.getErrorCode(),
					ResidentErrorCode.IO_EXCEPTION.getErrorMessage(), e);
		} catch (ApisResourceAccessException e) {
			logger.error("Error occured in accessing identity data %s", e.getMessage());
			throw new ResidentServiceCheckedException(ResidentErrorCode.IO_EXCEPTION.getErrorCode(),
					ResidentErrorCode.API_RESOURCE_ACCESS_EXCEPTION.getErrorMessage(), e);
		}
		logger.debug("IdentityServiceImpl::getIdentity()::exit");
		return identityDTO;
	}

	public String getFullName(Map<String, Object> identity, String langCode) throws ResidentServiceCheckedException, IOException {
		if(nameValueList==null){
			nameValueList= getNameValueFromIdentityMapping();
		}
		StringBuilder nameValue = new StringBuilder();
		for (String nameString : nameValueList) {
			nameValue.append(getValueFromIdentityMapping(nameString, identity, langCode));
		}
		return String.valueOf(nameValue);
	}

	private String getValueFromIdentityMapping(String nameString, Map<String, Object> identity, String langCode) {
		if (nameString == null || identity == null || langCode == null) {
			return ""; // Return early if any input is null
		}

		// Retrieve the identity value map
		Map<String, Object> identityValueMap = (Map<String, Object>) identity.get(IDENTITY);
		if (identityValueMap == null) {
			return ""; // Return early if identity map is null
		}

		// Retrieve the list of nameValueMap
		List<Map<String, Object>> nameValueMap = (List<Map<String, Object>>) identityValueMap.get(nameString);
		if (nameValueMap == null) {
			return ""; // Return early if the nameValueMap is null
		}

		// Use stream to find the matching language and return the corresponding value
		return nameValueMap.stream()
				.filter(nameMap -> langCode.equalsIgnoreCase((String) nameMap.get(ResidentConstants.LANGUAGE)))
				.map(nameMap -> (String) nameMap.get(ResidentConstants.VALUE))
				.findFirst() // Get the first matching value
				.orElse(""); // Return an empty string if no match is found
	}


	@PostConstruct
	public List<String> getNameValueFromIdentityMapping() throws ResidentServiceCheckedException {
		if (Objects.isNull(nameValueList)) {
			try {
				Map<String, Object> identityMappingMap = residentConfigService.getIdentityMappingMap();
                Map nameMap = (Map) identityMappingMap.get(ResidentConstants.NAME);
				String nameValue = (String) nameMap.get(ResidentConstants.VALUE);

				if(nameValue.contains(ResidentConstants.COMMA)){
					nameValueList = List.of(nameValue.split(ResidentConstants.COMMA));
				} else{
					nameValueList = List.of(nameValue);
				}
			} catch (IOException e) {
				logger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
						"getNameValueFromIdentityMapping",
						ResidentErrorCode.API_RESOURCE_UNAVAILABLE.getErrorCode() + org.apache.commons.lang3.exception.ExceptionUtils.getStackTrace(e));
				throw new ResidentServiceCheckedException(ResidentErrorCode.POLICY_EXCEPTION.getErrorCode(),
						ResidentErrorCode.POLICY_EXCEPTION.getErrorMessage(), e);
			}
		}
		return nameValueList;
	}
}
