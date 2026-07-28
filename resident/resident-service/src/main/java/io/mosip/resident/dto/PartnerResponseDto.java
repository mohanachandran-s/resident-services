package io.mosip.resident.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Date;

@Data
public class PartnerResponseDto {

	private String partnerId;

	private String approvalStatus;

	private String policyGroupName;
	
	private String policyGroupDescription;

	private String organizationName;

	private String contactNumber;

	private String emailId;

	private String partnerType;

	private Boolean isActive;
	
	private String firstName;
	
	private String lastName;

	private LocalDateTime createdDateTime;

	private Date certificateUploadDateTime;
	
	private Date certificateExpiryDateTime;

	private Boolean isCertificateAvailable;

	private String logoUrl;

	private JsonNode additionalInfo;
}