/**
 * 
 */
package io.mosip.registration.processor.stages.osivalidator.utils;

import java.io.IOException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.SecretKey;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.codec.binary.Base64;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.xml.sax.SAXException;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.mosip.kernel.bioapi.impl.BioApiImpl;
import io.mosip.kernel.cbeffutil.impl.CbeffImpl;
import io.mosip.kernel.core.bioapi.exception.BiometricException;
import io.mosip.kernel.core.bioapi.spi.IBioApi;
import io.mosip.kernel.core.cbeffutil.jaxbclasses.BIRType;
import io.mosip.kernel.core.cbeffutil.spi.CbeffUtil;
import io.mosip.kernel.core.crypto.spi.Encryptor;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.kernel.core.util.CryptoUtil;
import io.mosip.kernel.core.util.DateUtils;
import io.mosip.kernel.core.util.HMACUtils;
import io.mosip.kernel.keygenerator.bouncycastle.KeyGenerator;
import io.mosip.registration.processor.core.auth.dto.AuthRequestDTO;
import io.mosip.registration.processor.core.auth.dto.AuthResponseDTO;
import io.mosip.registration.processor.core.auth.dto.AuthTypeDTO;
import io.mosip.registration.processor.core.auth.dto.BioInfo;
import io.mosip.registration.processor.core.auth.dto.DataInfoDTO;
import io.mosip.registration.processor.core.auth.dto.PublicKeyResponseDto;
import io.mosip.registration.processor.core.auth.dto.RequestDTO;
import io.mosip.registration.processor.core.auth.util.BioSubTypeMapperUtil;
import io.mosip.registration.processor.core.auth.util.BioTypeMapperUtil;
import io.mosip.registration.processor.core.code.ApiName;
import io.mosip.registration.processor.core.code.BioSubType;
import io.mosip.registration.processor.core.code.BioType;
import io.mosip.registration.processor.core.constant.LoggerFileConstant;
import io.mosip.registration.processor.core.exception.ApisResourceAccessException;
import io.mosip.registration.processor.core.exception.BioTypeException;
import io.mosip.registration.processor.core.exception.util.PlatformErrorMessages;
import io.mosip.registration.processor.core.http.ResponseWrapper;
import io.mosip.registration.processor.core.logger.RegProcessorLogger;
import io.mosip.registration.processor.core.spi.restclient.RegistrationProcessorRestClientService;

/**
 * @author Ranjitha Siddegowda
 *
 */
public class AuthUtil {

	/** The reg proc logger. */
	private static Logger regProcLogger = RegProcessorLogger.getLogger(AuthUtil.class);


	/** The key generator. */
	@Autowired
	private KeyGenerator keyGenerator;

	/** The encryptor. */
	@Autowired
	private Encryptor<PrivateKey, PublicKey, SecretKey> encryptor;

	/** The registration processor rest client service. */
	@Autowired
	RegistrationProcessorRestClientService<Object> registrationProcessorRestClientService;

	private ObjectMapper mapper = new ObjectMapper();

	/** The Constant APPLICATION_ID. */
	public static final String IDA_APP_ID = "IDA";

	/** The Constant RSA. */
	public static final String RSA = "RSA";

	/** The Constant RSA. */
	public static final String PARTNER_ID = "PARTNER";

	BioTypeMapperUtil bioTypeMapperUtil = new BioTypeMapperUtil();

	BioSubTypeMapperUtil bioSubTypeMapperUtil = new BioSubTypeMapperUtil();

	IBioApi bioAPi =  new BioApiImpl ();

	CbeffUtil cbeffUtil = new CbeffImpl();

	public AuthResponseDTO authByIdAuthentication(String individualId,String individualType , byte[] biometricFile) throws ApisResourceAccessException, InvalidKeySpecException, NoSuchAlgorithmException, IOException, ParserConfigurationException, SAXException, BiometricException, BioTypeException {
		
		AuthRequestDTO authRequestDTO = new AuthRequestDTO();

		RequestDTO req = new RequestDTO();
		List<BioInfo> biometrics = new ArrayList<>();
		AuthTypeDTO authType = new AuthTypeDTO();
		authRequestDTO.setId("mosip.identity.auth");
		authRequestDTO.setIndividualId(individualId);
		authRequestDTO.setIndividualIdType(individualType);
		authRequestDTO.setRequestTime(DateUtils.getUTCCurrentDateTimeString());

		biometrics = getBioValue(biometricFile);

		req.setBiometrics(biometrics);
		req.setTimestamp(DateUtils.getUTCCurrentDateTimeString());

		authType.setBio(Boolean.TRUE);
		authRequestDTO.setRequestedAuth(authType);
		authRequestDTO.setTransactionID("1234567890");
		authRequestDTO.setVersion("1.0");

		String identityBlock = mapper.writeValueAsString(req);
		
		final SecretKey secretKey = keyGenerator.getSymmetricKey();

		byte[] encryptedIdentityBlock = encryptor.symmetricEncrypt(secretKey, identityBlock.getBytes());
		authRequestDTO.setRequest(Base64.encodeBase64URLSafeString(encryptedIdentityBlock));

		byte[] encryptedSessionKeyByte = encryptRSA(secretKey.getEncoded(), PARTNER_ID,
				DateUtils.getUTCCurrentDateTimeString());
		authRequestDTO.setRequestSessionKey(Base64.encodeBase64URLSafeString(encryptedSessionKeyByte));

		byte[] byteArr = encryptor.symmetricEncrypt(secretKey,
				HMACUtils.digestAsPlainText(HMACUtils.generateHash(identityBlock.getBytes())).getBytes());
		authRequestDTO.setRequestHMAC(Base64.encodeBase64String(byteArr));

		AuthResponseDTO response = (AuthResponseDTO) registrationProcessorRestClientService.postApi(ApiName.INTERNALAUTH,
				null, null, authRequestDTO, AuthResponseDTO.class, MediaType.APPLICATION_JSON);
		
		return response;

	}

	private byte[] encryptRSA(final byte[] sessionKey, String refId, String creationTime)
			throws ApisResourceAccessException, InvalidKeySpecException, java.security.NoSuchAlgorithmException,
			IOException {

		// encrypt AES Session Key using RSA public key
		List<String> pathsegments = new ArrayList<>();
		pathsegments.add(IDA_APP_ID);
		ResponseWrapper<?> responseWrapper;
		PublicKeyResponseDto publicKeyResponsedto = null;

		responseWrapper = (ResponseWrapper<?>) registrationProcessorRestClientService.getApi(ApiName.ENCRYPTIONSERVICE,
				pathsegments, "timeStamp,referenceId", creationTime + ',' + refId, ResponseWrapper.class);
		publicKeyResponsedto = mapper.readValue(mapper.writeValueAsString(responseWrapper.getResponse()),
				PublicKeyResponseDto.class);

		PublicKey publicKey = KeyFactory.getInstance(RSA)
				.generatePublic(new X509EncodedKeySpec(CryptoUtil.decodeBase64(publicKeyResponsedto.getPublicKey())));

		return encryptor.asymmetricPublicEncrypt(publicKey, sessionKey);

	}

	private DataInfoDTO getBioType(DataInfoDTO dataInfoDTO, String bioType) {
		if (bioType.equalsIgnoreCase(BioType.FINGER.toString())) {
			dataInfoDTO.setBioType(bioTypeMapperUtil.getStatusCode(BioType.FINGER));
		} else if (bioType.equalsIgnoreCase(BioType.FACE.toString())) {
			dataInfoDTO.setBioType(bioTypeMapperUtil.getStatusCode(BioType.FACE));
		} else if (bioType.equalsIgnoreCase(BioType.IRIS.toString())) {
			dataInfoDTO.setBioType(bioTypeMapperUtil.getStatusCode(BioType.IRIS));
		}
		return dataInfoDTO;
	}

	private DataInfoDTO getBioSubType(DataInfoDTO dataInfoDTO, String bioSubType) {
		if (bioSubType.equalsIgnoreCase(BioSubType.LEFT_INDEX_FINGER.getBioType())) {
			dataInfoDTO.setBioSubType(bioSubTypeMapperUtil.getStatusCode(BioSubType.LEFT_INDEX_FINGER));
		} else if (bioSubType.equalsIgnoreCase(BioSubType.LEFT_LITTLE_FINGER.getBioType())) {
			dataInfoDTO.setBioSubType(bioSubTypeMapperUtil.getStatusCode(BioSubType.LEFT_LITTLE_FINGER));
		} else if (bioSubType.equalsIgnoreCase(BioSubType.LEFT_MIDDLE_FINGER.getBioType())) {
			dataInfoDTO.setBioSubType(bioSubTypeMapperUtil.getStatusCode(BioSubType.LEFT_MIDDLE_FINGER));
		} else if (bioSubType.equalsIgnoreCase(BioSubType.LEFT_RING_FINGER.getBioType())) {
			dataInfoDTO.setBioSubType(bioSubTypeMapperUtil.getStatusCode(BioSubType.LEFT_RING_FINGER));
		} else if (bioSubType.equalsIgnoreCase(BioSubType.RIGHT_INDEX_FINGER.getBioType())) {
			dataInfoDTO.setBioSubType(bioSubTypeMapperUtil.getStatusCode(BioSubType.RIGHT_INDEX_FINGER));
		} else if (bioSubType.equalsIgnoreCase(BioSubType.RIGHT_LITTLE_FINGER.getBioType())) {
			dataInfoDTO.setBioSubType(bioSubTypeMapperUtil.getStatusCode(BioSubType.RIGHT_LITTLE_FINGER));
		} else if (bioSubType.equalsIgnoreCase(BioSubType.RIGHT_MIDDLE_FINGER.getBioType())) {
			dataInfoDTO.setBioSubType(bioSubTypeMapperUtil.getStatusCode(BioSubType.RIGHT_MIDDLE_FINGER));
		} else if (bioSubType.equalsIgnoreCase(BioSubType.RIGHT_RING_FINGER.getBioType())) {
			dataInfoDTO.setBioSubType(bioSubTypeMapperUtil.getStatusCode(BioSubType.RIGHT_RING_FINGER));
		} else if (bioSubType.equalsIgnoreCase(BioSubType.LEFT_THUMB.getBioType())) {
			dataInfoDTO.setBioSubType(bioSubTypeMapperUtil.getStatusCode(BioSubType.LEFT_THUMB));
		} else if (bioSubType.equalsIgnoreCase(BioSubType.RIGHT_THUMB.getBioType())) {
			dataInfoDTO.setBioSubType(bioSubTypeMapperUtil.getStatusCode(BioSubType.RIGHT_THUMB));
		} else if (bioSubType.equalsIgnoreCase(BioSubType.IRIS_LEFT.getBioType())) {
			dataInfoDTO.setBioSubType(bioSubTypeMapperUtil.getStatusCode(BioSubType.IRIS_LEFT));
		} else if (bioSubType.equalsIgnoreCase(BioSubType.IRIS_RIGHT.getBioType())) {
			dataInfoDTO.setBioSubType(bioSubTypeMapperUtil.getStatusCode(BioSubType.IRIS_RIGHT));
		} else if (bioSubType.equalsIgnoreCase("")) {
			dataInfoDTO.setBioSubType(bioSubTypeMapperUtil.getStatusCode(BioSubType.FACE));
		} else {
			dataInfoDTO.setBioSubType(bioSubTypeMapperUtil.getStatusCode(BioSubType.FACE));
		}
		return dataInfoDTO;
	}

	private List<BioInfo> getBioValue(byte[] cbefByteFile)
			throws BiometricException, BioTypeException {
		List<BIRType> list;
		

		List<BioInfo> biometrics = new ArrayList<>();
		try {
			list = cbeffUtil.getBIRDataFromXML(cbefByteFile);

			for (BIRType birType : list) {
				BioInfo bioInfo = new BioInfo();
				DataInfoDTO dataInfoDTO = new DataInfoDTO();
				BIRType birApiResponse = bioAPi.extractTemplate(birType, null);
				
				getBioType(dataInfoDTO, birApiResponse.getBDBInfo().getType().get(0).toString());

				StringBuilder bioSubTypeValue =new StringBuilder();  
				
				List<String> bioSubType = birApiResponse.getBDBInfo().getSubtype();
				if (!bioSubType.isEmpty()) {
					for (String value : bioSubType) {
						bioSubTypeValue.append(value);
					}
				}
				
				getBioSubType(dataInfoDTO, bioSubTypeValue.toString());
				
				dataInfoDTO.setBioValue(CryptoUtil.encodeBase64String(birApiResponse.getBDB()));
				dataInfoDTO.setDeviceProviderID("cogent");
				dataInfoDTO.setTimestamp(DateUtils.getUTCCurrentDateTimeString());
				dataInfoDTO.setTransactionID("1234567890");
				bioInfo.setData(dataInfoDTO);
				biometrics.add(bioInfo);
			}
			
			return biometrics;

		} catch (Exception e) {
			regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					"", PlatformErrorMessages.OSI_VALIDATION_BIO_TYPE_EXCEPTION.getMessage() + "-" + e.getMessage());
			throw new BioTypeException(
					PlatformErrorMessages.OSI_VALIDATION_BIO_TYPE_EXCEPTION.getMessage() + "-" + e.getMessage());

		}
	}
}
