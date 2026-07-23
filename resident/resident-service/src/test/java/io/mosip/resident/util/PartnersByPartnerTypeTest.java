package io.mosip.resident.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;

import io.mosip.kernel.core.exception.ServiceError;
import io.mosip.kernel.core.http.ResponseWrapper;
import io.mosip.resident.constant.ApiName;
import io.mosip.resident.exception.ApisResourceAccessException;
import io.mosip.resident.exception.ResidentServiceCheckedException;

/**
 * Tests for the partner manager v2 pagination and the v2 -> v1 backward
 * compatibility mapping in {@link PartnersByPartnerType}.
 *
 * @author Kamesh Shekhar Prasad
 */
@RunWith(MockitoJUnitRunner.class)
@RefreshScope
@ContextConfiguration
public class PartnersByPartnerTypeTest {

	@Mock
	private ResidentServiceRestClient residentServiceRestClient;

	@InjectMocks
	private PartnersByPartnerType partnersByPartnerType;

	@Captor
	private ArgumentCaptor<List<String>> queryParamNameCaptor;

	@Captor
	private ArgumentCaptor<List<Object>> queryParamValueCaptor;

	@Before
	public void setUp() {
		ReflectionTestUtils.setField(partnersByPartnerType, "pageSize", 10);
	}

	// ---------- helpers ----------

	/** Builds a single v2 partner record as the PMS v2 API returns it. */
	private Map<String, Object> v2Partner(String partnerId) {
		Map<String, Object> partner = new LinkedHashMap<>();
		partner.put("partnerId", partnerId);
		partner.put("partnerType", "Auth_Partner");
		partner.put("orgName", "IITB");
		partner.put("policyGroupId", "49327");
		partner.put("policyGroupName", "policygroup819");
		partner.put("emailAddress", "info@mosip.io");
		partner.put("certificateUploadStatus", "uploaded");
		partner.put("status", "active");
		partner.put("isActive", true);
		partner.put("createdDateTime", "2026-07-10T08:34:59.355+00:00");
		partner.put("logoUrl", "https://logo");
		partner.put("additionalInfo", null);
		return partner;
	}

	/** Wraps v2 records into one page response. */
	private ResponseWrapper<Object> page(int totalResults, List<Object> data) {
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("totalResults", totalResults);
		response.put("data", data);
		ResponseWrapper<Object> wrapper = new ResponseWrapper<>();
		wrapper.setErrors(new ArrayList<>());
		wrapper.setResponse(response);
		return wrapper;
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> partnersOf(ResponseWrapper<?> result) {
		Map<String, Object> response = (Map<String, Object>) result.getResponse();
		return (List<Map<String, Object>>) response.get("partners");
	}

	@SuppressWarnings("unchecked")
	private void stubGetApi(Object first, Object... rest) throws ApisResourceAccessException {
		when(residentServiceRestClient.getApi((ApiName) any(), (List<String>) any(), (List<String>) any(),
				(List<Object>) any(), (Class<Object>) any())).thenReturn(first, rest);
	}

	// ---------- v2 -> v1 mapping ----------

	@Test
	public void should_mapV2FieldsToV1Aliases_when_partnerReturned() throws Exception {
		stubGetApi(page(1, List.of(v2Partner("mpartner-default-auth"))));

		List<Map<String, Object>> partners = partnersOf(
				partnersByPartnerType.getPartnersByPartnerType(Optional.of("Auth_Partner"), ApiName.PARTNER_API_URL));

		assertEquals(1, partners.size());
		Map<String, Object> partner = partners.get(0);
		// v1 names the UI and OrderCard/Credential services read
		assertEquals("mpartner-default-auth", partner.get("partnerID"));
		assertEquals("IITB", partner.get("organizationName"));
		assertEquals("info@mosip.io", partner.get("emailId"));
	}

	@Test
	public void should_retainV2FieldsAlongsideV1Aliases_when_partnerReturned() throws Exception {
		stubGetApi(page(1, List.of(v2Partner("p1"))));

		Map<String, Object> partner = partnersOf(
				partnersByPartnerType.getPartnersByPartnerType(Optional.of("Auth_Partner"), ApiName.PARTNER_API_URL))
						.get(0);

		// original v2 keys are intentionally kept - extra keys must not be dropped
		assertEquals("p1", partner.get("partnerId"));
		assertEquals("IITB", partner.get("orgName"));
		assertEquals("info@mosip.io", partner.get("emailAddress"));
		// v2-only fields with no v1 equivalent survive the mapping
		assertEquals("49327", partner.get("policyGroupId"));
		assertEquals("uploaded", partner.get("certificateUploadStatus"));
		assertEquals("active", partner.get("status"));
		assertEquals("Auth_Partner", partner.get("partnerType"));
		assertEquals("https://logo", partner.get("logoUrl"));
		assertEquals(true, partner.get("isActive"));
	}

	@Test
	public void should_notAddContactNumberAndAddress_when_notReturnedByV2() throws Exception {
		stubGetApi(page(1, List.of(v2Partner("p1"))));

		Map<String, Object> partner = partnersOf(
				partnersByPartnerType.getPartnersByPartnerType(Optional.of("Auth_Partner"), ApiName.PARTNER_API_URL))
						.get(0);

		// v2 does not provide these and the mapper no longer synthesizes them
		assertFalse(partner.containsKey("contactNumber"));
		assertFalse(partner.containsKey("address"));
	}

	@Test
	public void should_keepContactNumberAndAddress_when_presentInV2() throws Exception {
		Map<String, Object> v2 = v2Partner("p1");
		v2.put("contactNumber", "821-748-9064");
		v2.put("address", "Albert Flats");
		stubGetApi(page(1, List.of(v2)));

		Map<String, Object> partner = partnersOf(
				partnersByPartnerType.getPartnersByPartnerType(Optional.of("Auth_Partner"), ApiName.PARTNER_API_URL))
						.get(0);

		// any field v2 sends is preserved by the map copy
		assertEquals("821-748-9064", partner.get("contactNumber"));
		assertEquals("Albert Flats", partner.get("address"));
	}

	@Test
	public void should_mapPartnerIdToNullAlias_when_partnerIdNull() throws Exception {
		Map<String, Object> v2 = v2Partner(null);
		stubGetApi(page(1, List.of(v2)));

		Map<String, Object> partner = partnersOf(
				partnersByPartnerType.getPartnersByPartnerType(Optional.of("Auth_Partner"), ApiName.PARTNER_API_URL))
						.get(0);

		assertNull(partner.get("partnerID"));
	}

	// ---------- pagination ----------

	@Test
	public void should_fetchAndMergeAllPages_when_multiplePages() throws Exception {
		ReflectionTestUtils.setField(partnersByPartnerType, "pageSize", 2);
		stubGetApi(page(5, List.of(v2Partner("p1"), v2Partner("p2"))),
				page(5, List.of(v2Partner("p3"), v2Partner("p4"))),
				page(5, List.of(v2Partner("p5"))));

		List<Map<String, Object>> partners = partnersOf(
				partnersByPartnerType.getPartnersByPartnerType(Optional.of("Auth_Partner"), ApiName.PARTNER_API_URL));

		assertEquals(5, partners.size());
		assertEquals("p1", partners.get(0).get("partnerID"));
		assertEquals("p5", partners.get(4).get("partnerID"));
		verify(residentServiceRestClient, times(3)).getApi((ApiName) any(), (List<String>) any(), (List<String>) any(),
				(List<Object>) any(), (Class<Object>) any());
	}

	@Test
	public void should_fetchSinglePage_when_resultsFitInOnePage() throws Exception {
		stubGetApi(page(2, List.of(v2Partner("p1"), v2Partner("p2"))));

		List<Map<String, Object>> partners = partnersOf(
				partnersByPartnerType.getPartnersByPartnerType(Optional.of("Auth_Partner"), ApiName.PARTNER_API_URL));

		assertEquals(2, partners.size());
		verify(residentServiceRestClient, times(1)).getApi((ApiName) any(), (List<String>) any(), (List<String>) any(),
				(List<Object>) any(), (Class<Object>) any());
	}

	@Test
	public void should_stopFetching_when_pageReturnsEmptyData() throws Exception {
		ReflectionTestUtils.setField(partnersByPartnerType, "pageSize", 2);
		// totalResults claims 10 but the second page is empty - must not loop forever
		stubGetApi(page(10, List.of(v2Partner("p1"), v2Partner("p2"))), page(10, new ArrayList<>()));

		List<Map<String, Object>> partners = partnersOf(
				partnersByPartnerType.getPartnersByPartnerType(Optional.of("Auth_Partner"), ApiName.PARTNER_API_URL));

		assertEquals(2, partners.size());
		verify(residentServiceRestClient, times(2)).getApi((ApiName) any(), (List<String>) any(), (List<String>) any(),
				(List<Object>) any(), (Class<Object>) any());
	}

	@Test
	public void should_incrementPageNo_when_fetchingMultiplePages() throws Exception {
		ReflectionTestUtils.setField(partnersByPartnerType, "pageSize", 2);
		stubGetApi(page(3, List.of(v2Partner("p1"), v2Partner("p2"))), page(3, List.of(v2Partner("p3"))));

		partnersByPartnerType.getPartnersByPartnerType(Optional.of("Auth_Partner"), ApiName.PARTNER_API_URL);

		verify(residentServiceRestClient, times(2)).getApi((ApiName) any(), (List<String>) any(),
				queryParamNameCaptor.capture(), queryParamValueCaptor.capture(), (Class<Object>) any());
		List<List<Object>> values = queryParamValueCaptor.getAllValues();
		// partnerType, pageSize, pageNo
		assertEquals(0, values.get(0).get(2));
		assertEquals(1, values.get(1).get(2));
	}

	// ---------- query params ----------

	@Test
	public void should_sendPartnerTypeQueryParam_when_partnerTypePresent() throws Exception {
		stubGetApi(page(1, List.of(v2Partner("p1"))));

		partnersByPartnerType.getPartnersByPartnerType(Optional.of("Auth_Partner"), ApiName.PARTNER_API_URL);

		verify(residentServiceRestClient).getApi((ApiName) any(), (List<String>) any(),
				queryParamNameCaptor.capture(), queryParamValueCaptor.capture(), (Class<Object>) any());
		assertEquals(List.of("partnerType", "pageSize", "pageNo"), queryParamNameCaptor.getValue());
		assertEquals("Auth_Partner", queryParamValueCaptor.getValue().get(0));
	}

	@Test
	public void should_omitPartnerTypeQueryParam_when_partnerTypeEmpty() throws Exception {
		stubGetApi(page(1, List.of(v2Partner("p1"))));

		partnersByPartnerType.getPartnersByPartnerType(Optional.empty(), ApiName.PARTNER_API_URL);

		verify(residentServiceRestClient).getApi((ApiName) any(), (List<String>) any(),
				queryParamNameCaptor.capture(), queryParamValueCaptor.capture(), (Class<Object>) any());
		assertEquals(List.of("pageSize", "pageNo"), queryParamNameCaptor.getValue());
	}

	@Test
	public void should_fallBackToDefaultPageSize_when_pageSizeZero() throws Exception {
		ReflectionTestUtils.setField(partnersByPartnerType, "pageSize", 0);
		stubGetApi(page(1, List.of(v2Partner("p1"))));

		partnersByPartnerType.getPartnersByPartnerType(Optional.of("Auth_Partner"), ApiName.PARTNER_API_URL);

		verify(residentServiceRestClient).getApi((ApiName) any(), (List<String>) any(),
				queryParamNameCaptor.capture(), queryParamValueCaptor.capture(), (Class<Object>) any());
		// pageSize is the second query param and must be the 10 fallback, not 0
		assertEquals(10, queryParamValueCaptor.getValue().get(1));
	}

	@Test
	public void should_fallBackToDefaultPageSize_when_pageSizeNegative() throws Exception {
		ReflectionTestUtils.setField(partnersByPartnerType, "pageSize", -5);
		stubGetApi(page(1, List.of(v2Partner("p1"))));

		partnersByPartnerType.getPartnersByPartnerType(Optional.of("Auth_Partner"), ApiName.PARTNER_API_URL);

		verify(residentServiceRestClient).getApi((ApiName) any(), (List<String>) any(),
				queryParamNameCaptor.capture(), queryParamValueCaptor.capture(), (Class<Object>) any());
		assertEquals(10, queryParamValueCaptor.getValue().get(1));
	}

	// ---------- empty / error paths ----------

	@Test
	public void should_returnEmptyPartnerList_when_responseNull() throws Exception {
		ResponseWrapper<Object> wrapper = new ResponseWrapper<>();
		wrapper.setErrors(new ArrayList<>());
		wrapper.setResponse(null);
		stubGetApi(wrapper);

		List<Map<String, Object>> partners = partnersOf(
				partnersByPartnerType.getPartnersByPartnerType(Optional.of("Auth_Partner"), ApiName.PARTNER_API_URL));

		assertTrue(partners.isEmpty());
	}

	@Test
	public void should_returnEmptyPartnerList_when_dataEmpty() throws Exception {
		stubGetApi(page(0, new ArrayList<>()));

		List<Map<String, Object>> partners = partnersOf(
				partnersByPartnerType.getPartnersByPartnerType(Optional.of("Auth_Partner"), ApiName.PARTNER_API_URL));

		assertTrue(partners.isEmpty());
	}

	@Test
	public void should_returnEmptyPartnerList_when_dataKeyMissing() throws Exception {
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("totalResults", 5);
		ResponseWrapper<Object> wrapper = new ResponseWrapper<>();
		wrapper.setErrors(new ArrayList<>());
		wrapper.setResponse(response);
		stubGetApi(wrapper);

		List<Map<String, Object>> partners = partnersOf(
				partnersByPartnerType.getPartnersByPartnerType(Optional.of("Auth_Partner"), ApiName.PARTNER_API_URL));

		assertTrue(partners.isEmpty());
	}

	@Test(expected = ResidentServiceCheckedException.class)
	public void should_throwResidentServiceCheckedException_when_responseHasErrors() throws Exception {
		ResponseWrapper<Object> wrapper = new ResponseWrapper<>();
		wrapper.setErrors(List.of(new ServiceError("RES-SER-441", "Exception while calling partner service")));
		stubGetApi(wrapper);

		partnersByPartnerType.getPartnersByPartnerType(Optional.of("Auth_Partner"), ApiName.PARTNER_API_URL);
	}

	@Test(expected = ResidentServiceCheckedException.class)
	public void should_wrapApisResourceAccessException_when_restClientFails() throws Exception {
		when(residentServiceRestClient.getApi((ApiName) any(), (List<String>) any(), (List<String>) any(),
				(List<Object>) any(), (Class<Object>) any())).thenThrow(new ApisResourceAccessException());

		partnersByPartnerType.getPartnersByPartnerType(Optional.of("Auth_Partner"), ApiName.PARTNER_API_URL);
	}

	@Test
	public void should_wrapResponseUnderPartnersKey_when_partnersReturned() throws Exception {
		stubGetApi(page(1, List.of(v2Partner("p1"))));

		ResponseWrapper<?> result = partnersByPartnerType.getPartnersByPartnerType(Optional.of("Auth_Partner"),
				ApiName.PARTNER_API_URL);

		@SuppressWarnings("unchecked")
		Map<String, Object> response = (Map<String, Object>) result.getResponse();
		// v1 contract exposes only "partners"; v2 pagination fields are not surfaced
		assertTrue(response.containsKey("partners"));
		assertEquals(1, response.size());
	}
}
