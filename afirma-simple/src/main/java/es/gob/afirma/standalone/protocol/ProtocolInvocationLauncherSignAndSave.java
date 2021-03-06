/* Copyright (C) 2011 [Gobierno de Espana]
 * This file is part of "Cliente @Firma".
 * "Cliente @Firma" is free software; you can redistribute it and/or modify it under the terms of:
 *   - the GNU General Public License as published by the Free Software Foundation;
 *     either version 2 of the License, or (at your option) any later version.
 *   - or The European Software License; either version 1.1 or (at your option) any later version.
 * You may contact the copyright holder at: soporte.afirma@seap.minhap.es
 */

package es.gob.afirma.standalone.protocol;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore.PrivateKeyEntry;
import java.security.MessageDigest;
import java.security.cert.CertificateEncodingException;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.security.auth.callback.PasswordCallback;

import es.gob.afirma.core.AOCancelledOperationException;
import es.gob.afirma.core.AOException;
import es.gob.afirma.core.AOFormatFileException;
import es.gob.afirma.core.AOInvalidFormatException;
import es.gob.afirma.core.misc.AOUtil;
import es.gob.afirma.core.misc.Base64;
import es.gob.afirma.core.misc.Platform;
import es.gob.afirma.core.misc.protocol.UrlParametersToSignAndSave;
import es.gob.afirma.core.misc.protocol.UrlParametersToSignAndSave.Operation;
import es.gob.afirma.core.signers.AOSignConstants;
import es.gob.afirma.core.signers.AOSigner;
import es.gob.afirma.core.signers.AOSignerFactory;
import es.gob.afirma.core.signers.CounterSignTarget;
import es.gob.afirma.core.signers.ExtraParamsProcessor.IncompatiblePolicyException;
import es.gob.afirma.core.signers.OptionalDataInterface;
import es.gob.afirma.core.ui.AOUIFactory;
import es.gob.afirma.core.ui.GenericFileFilter;
import es.gob.afirma.keystores.AOCertificatesNotFoundException;
import es.gob.afirma.keystores.AOKeyStore;
import es.gob.afirma.keystores.AOKeyStoreDialog;
import es.gob.afirma.keystores.AOKeyStoreManager;
import es.gob.afirma.keystores.AOKeyStoreManagerFactory;
import es.gob.afirma.keystores.filters.CertFilterManager;
import es.gob.afirma.keystores.filters.CertificateFilter;
import es.gob.afirma.signers.pades.BadPdfPasswordException;
import es.gob.afirma.signers.pades.InvalidPdfException;
import es.gob.afirma.signers.pades.PdfHasUnregisteredSignaturesException;
import es.gob.afirma.signers.pades.PdfIsCertifiedException;
import es.gob.afirma.signers.pades.PdfIsPasswordProtectedException;
import es.gob.afirma.signers.xades.EFacturaAlreadySignedException;
import es.gob.afirma.signers.xml.InvalidXMLException;
import es.gob.afirma.standalone.AutoFirmaUtil;
import es.gob.afirma.standalone.DataAnalizerUtil;
import es.gob.afirma.standalone.crypto.CypherDataManager;
import es.gob.afirma.standalone.so.macos.MacUtils;

final class ProtocolInvocationLauncherSignAndSave {

	private static final char CERT_SIGNATURE_SEPARATOR = '|';

	private static final String RESULT_CANCEL = "CANCEL"; //$NON-NLS-1$

	private static final Logger LOGGER = Logger.getLogger("es.gob.afirma"); //$NON-NLS-1$

	private ProtocolInvocationLauncherSignAndSave() {
		// No instanciable
	}

	/** Procesa una peticion de firma y guardado en invocaci&oacute;n por protocolo y obtiene
	 * la firma junto con una serie de metadatos en forma de cadena.
	 * @param options Par&aacute;metros de la operaci&oacute;n.
	 * @param protocolVersion Versi&oacute;n del protocolo de comunicaci&oacute;n.
	 * @param bySocket <code>true</code> para usar comunicaci&oacute;n por <i>socket</i> local,
	 *                 <code>false</code> para usar servidor intermedio.
	 * @return Resultado de la operaci&oacute;n o mensaje de error.
	 * @throws SocketOperationException Si hay errores en la
	 *                                  comunicaci&oacute;n por <i>socket</i> local. */
	static String process(final UrlParametersToSignAndSave options,
			final int protocolVersion,
			final boolean bySocket) throws SocketOperationException {

		if (options == null) {
			LOGGER.severe("Las opciones de firma son nulas"); //$NON-NLS-1$
			ProtocolInvocationLauncherErrorManager.showError(
				ProtocolInvocationLauncherErrorManager.ERROR_NULL_URI
			);
			if (!bySocket) {
				throw new SocketOperationException(ProtocolInvocationLauncherErrorManager.ERROR_NULL_URI);
			}
			return ProtocolInvocationLauncherErrorManager.getErrorMessage(
				ProtocolInvocationLauncherErrorManager.ERROR_NULL_URI
			);
		}

		if (!ProtocolInvocationLauncher.MAX_PROTOCOL_VERSION_SUPPORTED.support(protocolVersion)) {
			LOGGER.severe(String.format(
				"Version de protocolo no soportada (%1s). Version actual: %s2. Hay que actualizar la aplicacion.", //$NON-NLS-1$
				Integer.valueOf(protocolVersion),
				Integer.valueOf(ProtocolInvocationLauncher.MAX_PROTOCOL_VERSION_SUPPORTED.getVersion())));
			ProtocolInvocationLauncherErrorManager.showError(
				ProtocolInvocationLauncherErrorManager.ERROR_UNSUPPORTED_PROCEDURE
			);
			return ProtocolInvocationLauncherErrorManager.getErrorMessage(
				ProtocolInvocationLauncherErrorManager.ERROR_UNSUPPORTED_PROCEDURE
			);
		}

		// En caso de que no se haya solicitado una operacion de multifirma con el formato AUTO
		// configuramos el servidor en base al nombre de formato
		AOSigner signer = null;
		if (!AOSignConstants.SIGN_FORMAT_AUTO.equalsIgnoreCase(options.getSignatureFormat())) {
			signer = AOSignerFactory.getSigner(options.getSignatureFormat());
			if (signer == null) {
				LOGGER.severe("No hay un firmador configurado para el formato: " + options.getSignatureFormat()); //$NON-NLS-1$
				ProtocolInvocationLauncherErrorManager.showError(
					ProtocolInvocationLauncherErrorManager.ERROR_UNSUPPORTED_FORMAT
				);
				if (!bySocket) {
					throw new SocketOperationException(
						ProtocolInvocationLauncherErrorManager.ERROR_UNSUPPORTED_FORMAT
					);
				}
				return ProtocolInvocationLauncherErrorManager.getErrorMessage(
					ProtocolInvocationLauncherErrorManager.ERROR_UNSUPPORTED_FORMAT
				);
			}
		}

		final AOKeyStore aoks = AOKeyStore.getKeyStore(options.getDefaultKeyStore());
		if (aoks == null) {
			LOGGER.severe("No hay un KeyStore asociado al valor: " + options.getDefaultKeyStore()); //$NON-NLS-1$
			ProtocolInvocationLauncherErrorManager.showError(
				ProtocolInvocationLauncherErrorManager.ERROR_CANNOT_FIND_KEYSTORE
			);
			if (!bySocket) {
				throw new SocketOperationException(
					ProtocolInvocationLauncherErrorManager.ERROR_CANNOT_FIND_KEYSTORE
				);
			}
			return ProtocolInvocationLauncherErrorManager.getErrorMessage(
				ProtocolInvocationLauncherErrorManager.ERROR_CANNOT_FIND_KEYSTORE
			);
		}

		// Comprobamos si es necesario pedir datos de entrada al usuario
		boolean needRequestData = false;
		if (options.getData() == null) {
			if (signer != null && signer instanceof OptionalDataInterface) {
				needRequestData = ((OptionalDataInterface) signer).needData(options.getExtraParams());
			}
			else {
				needRequestData = true;
			}
		}

		// Nombre dl fichero firmado. Tomara valor solo si es ekl usuario quien selecciona
		// el fichero a firmar
		String inputFilename = null;

		// Si se tienen que pedir los datos al usuario, se hace
		if (needRequestData) {

			final String dialogTitle = Operation.SIGN.equals(options.getOperation()) ?
				ProtocolMessages.getString("ProtocolLauncher.25") : //$NON-NLS-1$
					ProtocolMessages.getString("ProtocolLauncher.26"); //$NON-NLS-1$

			final String fileExts = options.getExtraParams().getProperty("filenameExts"); //$NON-NLS-1$

			final String fileDesc = options.getExtraParams().getProperty("filenameDescription", ProtocolMessages.getString("ProtocolLauncher.32")) +  //$NON-NLS-1$//$NON-NLS-2$
				(fileExts == null ? " (*.*)" : String.format(" (*.%1s)", fileExts.replace(",", ",*."))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

			final File selectedDataFile;
			try {
				if (Platform.OS.MACOSX.equals(Platform.getOS())) {
					MacUtils.focusApplication();
				}

				selectedDataFile = AOUIFactory.getLoadFiles(
					dialogTitle,
					options.getExtraParams().getProperty("filenameCurrentDir"), // currentDir //$NON-NLS-1$
					options.getExtraParams().getProperty("filenameActualName"), // fileName //$NON-NLS-1$
					fileExts != null ? fileExts.split(",") : null, //$NON-NLS-1$
					fileDesc,
					false, // Select dir
					false, // Multiselect
					AutoFirmaUtil.getDefaultDialogsIcon(),
					null //Parent
				)[0];
			}
			catch (final AOCancelledOperationException e) {
				LOGGER.info("carga de datos de firma cancelada por el usuario: " + e); //$NON-NLS-1$
				if (!bySocket) {
					throw new SocketOperationException(getResultCancel());
				}
				return getResultCancel();
			}

			inputFilename = selectedDataFile.getName();

			try {
				final byte[] data;
				try (
					final InputStream fis = new FileInputStream(selectedDataFile);
					final InputStream bis = new BufferedInputStream(fis);
				) {
					data = AOUtil.getDataFromInputStream(bis);
				}
				if (data == null) {
					throw new IOException("La lectura de datos para firmar ha devuelto un nulo"); //$NON-NLS-1$
				}
				options.setData(data);
			}
			catch (final Exception e) {
				LOGGER.severe("Error en la lectura de los datos a firmar: " + e); //$NON-NLS-1$
				ProtocolInvocationLauncherErrorManager.showError(ProtocolInvocationLauncherErrorManager.ERROR_CANNOT_READ_DATA);
				if (!bySocket) {
					throw new SocketOperationException(ProtocolInvocationLauncherErrorManager.ERROR_CANNOT_READ_DATA);
				}
				return ProtocolInvocationLauncherErrorManager
					.getErrorMessage(ProtocolInvocationLauncherErrorManager.ERROR_CANNOT_READ_DATA);
			}
		}

		// En caso de haber programado el formato "AUTO", se selecciona el
		// firmador a partir de los datos (firma) proporcionados
		if (signer == null) {
			if (Operation.SIGN == options.getOperation()) {
				if (DataAnalizerUtil.isPDF(options.getData())) {
					signer = AOSignerFactory.getSigner(AOSignConstants.SIGN_FORMAT_PADES);
					options.setSignFormat(AOSignConstants.SIGN_FORMAT_PADES);
				}
				else if (DataAnalizerUtil.isFacturae(options.getData())) {
					signer = AOSignerFactory.getSigner(AOSignConstants.SIGN_FORMAT_FACTURAE);
					options.setSignFormat(AOSignConstants.SIGN_FORMAT_FACTURAE);
				}
				else if (DataAnalizerUtil.isXML(options.getData())) {
					signer = AOSignerFactory.getSigner(AOSignConstants.SIGN_FORMAT_XADES);
					options.setSignFormat(AOSignConstants.SIGN_FORMAT_XADES);
				}
				else if (DataAnalizerUtil.isODF(options.getData())) {
					signer = AOSignerFactory.getSigner(AOSignConstants.SIGN_FORMAT_ODF);
					options.setSignFormat(AOSignConstants.SIGN_FORMAT_ODF);
				}
				else if (DataAnalizerUtil.isOOXML(options.getData())) {
					signer = AOSignerFactory.getSigner(AOSignConstants.SIGN_FORMAT_OOXML);
					options.setSignFormat(AOSignConstants.SIGN_FORMAT_OOXML);
				}
				else {
					signer = AOSignerFactory.getSigner(AOSignConstants.SIGN_FORMAT_CADES);
					options.setSignFormat(AOSignConstants.SIGN_FORMAT_CADES);
				}
			}
			else {
				try {
					signer = AOSignerFactory.getSigner(options.getData());
				}
				catch (final IOException e) {
					LOGGER.severe(
						"No se han podido analizar los datos para determinar si son una firma: " + e //$NON-NLS-1$
					);
					// signer sera null
				}
			}

			if (signer == null) {
				LOGGER.severe(
					"Los datos no se corresponden con una firma electronica o no se pudieron analizar" //$NON-NLS-1$
				);
				ProtocolInvocationLauncherErrorManager.showError(
					ProtocolInvocationLauncherErrorManager.ERROR_UNKNOWN_SIGNER
				);
				if (!bySocket) {
					throw new SocketOperationException(ProtocolInvocationLauncherErrorManager.ERROR_UNKNOWN_SIGNER);
				}
				return ProtocolInvocationLauncherErrorManager.getErrorMessage(
					ProtocolInvocationLauncherErrorManager.ERROR_UNKNOWN_SIGNER
				);
			}
		}

		// XXX: Codigo de soporte de firmas XAdES explicitas (Eliminar cuando se
		// abandone el soporte de XAdES explicitas)
		if (options.getOperation() == Operation.SIGN && isXadesExplicitConfigurated(options.getSignatureFormat(), options.getExtraParams())) {
			LOGGER.warning(
				"Se ha pedido una firma XAdES explicita, este formato dejara de soportarse en proximas versiones" //$NON-NLS-1$
			);
			try {
				options.setData(MessageDigest.getInstance("SHA1").digest(options.getData())); //$NON-NLS-1$
				options.getExtraParams().setProperty("mimeType", "hash/sha1"); //$NON-NLS-1$ //$NON-NLS-2$
			}
			catch (final Exception e) {
				LOGGER.warning("Error al generar la huella digital de los datos para firmar como 'XAdES explicit', " //$NON-NLS-1$
					+ "se realizara una firma XAdES corriente: " + e); //$NON-NLS-1$
			}
		}

		// Una vez se tienen todos los parametros necesarios expandimos los extraParams
		// de la operacion para obtener la configuracion final
		try {
			options.expandExtraParams();
		}
		catch (final IncompatiblePolicyException e1) {
			LOGGER.info("Se ha indicado una politica no compatible: " + e1); //$NON-NLS-1$
			if (!bySocket) {
				throw new SocketOperationException(ProtocolInvocationLauncherErrorManager.ERROR_INVALID_POLICY);
			}
			return ProtocolInvocationLauncherErrorManager.getErrorMessage(
				ProtocolInvocationLauncherErrorManager.ERROR_INVALID_POLICY
			);
		}

		final CertFilterManager filterManager = new CertFilterManager(options.getExtraParams());
		final List<CertificateFilter> filters = filterManager.getFilters();
		final boolean mandatoryCertificate = filterManager.isMandatoryCertificate();
		final PrivateKeyEntry pke;

		if (options.getSticky() && ProtocolInvocationLauncher.getStickyKeyEntry() != null) {
			pke = ProtocolInvocationLauncher.getStickyKeyEntry();
		}
		else {

			final PasswordCallback pwc = aoks.getStorePasswordCallback(null);
			final String aoksLib = options.getDefaultKeyStoreLib();
			final AOKeyStoreManager ksm;
			try {
				ksm = AOKeyStoreManagerFactory.getAOKeyStoreManager(aoks, // Store
					aoksLib, // Lib
					null, // Description
					pwc, // PasswordCallback
					null // Parent
				);
			}
			catch (final Exception e3) {
				LOGGER.severe("Error obteniendo el AOKeyStoreManager: " + e3); //$NON-NLS-1$
				ProtocolInvocationLauncherErrorManager.showError(
					ProtocolInvocationLauncherErrorManager.ERROR_CANNOT_ACCESS_KEYSTORE
				);
				if (!bySocket) {
					throw new SocketOperationException(ProtocolInvocationLauncherErrorManager.ERROR_CANNOT_ACCESS_KEYSTORE);
				}
				return ProtocolInvocationLauncherErrorManager.getErrorMessage(
					ProtocolInvocationLauncherErrorManager.ERROR_CANNOT_ACCESS_KEYSTORE
				);
			}

			LOGGER.info("Obtenido gestor de almacenes de claves: " + ksm); //$NON-NLS-1$
			LOGGER.info("Cargando dialogo de seleccion de certificados..."); //$NON-NLS-1$

			try {
				MacUtils.focusApplication();
				final AOKeyStoreDialog dialog = new AOKeyStoreDialog(ksm, null, true, true, // showExpiredCertificates
					true, // checkValidity
					filters,
					mandatoryCertificate
				);
				dialog.allowOpenExternalStores(filterManager.isExternalStoresOpeningAllowed());
				dialog.show();
				pke = ksm.getKeyEntry(dialog.getSelectedAlias());

				if (options.getSticky()) {
					ProtocolInvocationLauncher.setStickyKeyEntry(pke);
				}
				else {
					ProtocolInvocationLauncher.setStickyKeyEntry(null);
				}
			}
			catch (final AOCancelledOperationException e) {
				LOGGER.severe("Operacion cancelada por el usuario: " + e); //$NON-NLS-1$
				if (!bySocket) {
					throw new SocketOperationException(getResultCancel());
				}
				return getResultCancel();
			}
			catch (final AOCertificatesNotFoundException e) {
				LOGGER.severe("No hay certificados validos en el almacen: " + e); //$NON-NLS-1$
				ProtocolInvocationLauncherErrorManager.showError(
					ProtocolInvocationLauncherErrorManager.ERROR_NO_CERTIFICATES_KEYSTORE
				);
				if (!bySocket) {
					throw new SocketOperationException(ProtocolInvocationLauncherErrorManager.ERROR_NO_CERTIFICATES_KEYSTORE);
				}
				return ProtocolInvocationLauncherErrorManager
						.getErrorMessage(ProtocolInvocationLauncherErrorManager.ERROR_NO_CERTIFICATES_KEYSTORE);
			}
			catch (final Exception e) {
				LOGGER.severe("Error al mostrar el dialogo de seleccion de certificados: " + e); //$NON-NLS-1$
				ProtocolInvocationLauncherErrorManager.showError(
					ProtocolInvocationLauncherErrorManager.ERROR_CANNOT_ACCESS_KEYSTORE
				);
				if (!bySocket) {
					throw new SocketOperationException(
						ProtocolInvocationLauncherErrorManager.ERROR_CANNOT_ACCESS_KEYSTORE
					);
				}
				return ProtocolInvocationLauncherErrorManager.getErrorMessage(
					ProtocolInvocationLauncherErrorManager.ERROR_CANNOT_ACCESS_KEYSTORE
				);
			}
		}

		LOGGER.info("Iniciando operacion de firma..."); //$NON-NLS-1$

		final byte[] sign;
		try {
			switch (options.getOperation()) {
				case SIGN:
					sign = signer.sign(
						options.getData(),
						options.getSignatureAlgorithm(),
						pke.getPrivateKey(),
						pke.getCertificateChain(),
						options.getExtraParams()
					);
				break;
			case COSIGN:
				sign = signer.cosign(
					options.getData(),
					options.getSignatureAlgorithm(),
					pke.getPrivateKey(),
					pke.getCertificateChain(),
					options.getExtraParams()
				);
				break;
			case COUNTERSIGN:
				sign = signer.countersign(
					options.getData(),
					options.getSignatureAlgorithm(),
					"tree".equalsIgnoreCase(options.getExtraParams().getProperty("target")) ? //$NON-NLS-1$ //$NON-NLS-2$
						CounterSignTarget.TREE :
							CounterSignTarget.LEAFS,
					null, // Targets
					pke.getPrivateKey(),
					pke.getCertificateChain(),
					options.getExtraParams()
				);
				break;
			default:
				LOGGER.severe("Error al realizar la operacion firma"); //$NON-NLS-1$
				ProtocolInvocationLauncherErrorManager.showError(
					ProtocolInvocationLauncherErrorManager.ERROR_UNSUPPORTED_OPERATION
				);
				if (!bySocket) {
					throw new SocketOperationException(
						ProtocolInvocationLauncherErrorManager.ERROR_UNSUPPORTED_OPERATION
					);
				}
				return ProtocolInvocationLauncherErrorManager.getErrorMessage(
					ProtocolInvocationLauncherErrorManager.ERROR_UNSUPPORTED_OPERATION
				);
			}
		}
		catch (final SocketOperationException e) {
			throw e;
		}
		catch (final IllegalArgumentException e) {
			LOGGER.log(Level.SEVERE, "Error al realizar la operacion de firma: " + e, e); //$NON-NLS-1$
			ProtocolInvocationLauncherErrorManager.showErrorDetail(ProtocolInvocationLauncherErrorManager.ERROR_PARAMS, e.getMessage());
			if (!bySocket) {
				throw new SocketOperationException(ProtocolInvocationLauncherErrorManager.ERROR_PARAMS);
			}
			return ProtocolInvocationLauncherErrorManager
					.getErrorMessage(ProtocolInvocationLauncherErrorManager.ERROR_PARAMS);
		}
		catch (final InvalidPdfException e) {
			LOGGER.log(Level.SEVERE, "Error al realizar la operacion de firma: " + e, e); //$NON-NLS-1$
			ProtocolInvocationLauncherErrorManager.showError(ProtocolInvocationLauncherErrorManager.ERROR_INVALID_PDF);
			if (!bySocket) {
				throw new SocketOperationException(ProtocolInvocationLauncherErrorManager.ERROR_INVALID_PDF);
			}
			return ProtocolInvocationLauncherErrorManager
					.getErrorMessage(ProtocolInvocationLauncherErrorManager.ERROR_INVALID_PDF);
		}
		catch (final InvalidXMLException e) {
			LOGGER.log(Level.SEVERE, "Error al realizar la operacion de firma: " + e, e); //$NON-NLS-1$
			ProtocolInvocationLauncherErrorManager.showError(ProtocolInvocationLauncherErrorManager.ERROR_INVALID_XML);
			if (!bySocket) {
				throw new SocketOperationException(ProtocolInvocationLauncherErrorManager.ERROR_INVALID_XML);
			}
			return ProtocolInvocationLauncherErrorManager
					.getErrorMessage(ProtocolInvocationLauncherErrorManager.ERROR_INVALID_XML);
		}
		catch (final AOFormatFileException e) {
			LOGGER.log(Level.SEVERE, "Error al realizar la operacion de firma: " + e, e); //$NON-NLS-1$
			ProtocolInvocationLauncherErrorManager.showError(ProtocolInvocationLauncherErrorManager.ERROR_INVALID_DATA);
			if (!bySocket) {
				throw new SocketOperationException(ProtocolInvocationLauncherErrorManager.ERROR_INVALID_DATA);
			}
			return ProtocolInvocationLauncherErrorManager
					.getErrorMessage(ProtocolInvocationLauncherErrorManager.ERROR_INVALID_DATA);
		}
		catch (final EFacturaAlreadySignedException e) {
			LOGGER.log(Level.SEVERE, "Error al realizar la operacion de firma: " + e, e); //$NON-NLS-1$
			ProtocolInvocationLauncherErrorManager.showError(ProtocolInvocationLauncherErrorManager.ERROR_FACE_ALREADY_SIGNED);
			if (!bySocket) {
				throw new SocketOperationException(ProtocolInvocationLauncherErrorManager.ERROR_FACE_ALREADY_SIGNED);
			}
			return ProtocolInvocationLauncherErrorManager
					.getErrorMessage(ProtocolInvocationLauncherErrorManager.ERROR_FACE_ALREADY_SIGNED);
		}
		catch (final AOInvalidFormatException e) {
			LOGGER.log(Level.SEVERE, "Error al realizar la operacion de firma: " + e, e); //$NON-NLS-1$
			ProtocolInvocationLauncherErrorManager.showError(ProtocolInvocationLauncherErrorManager.ERROR_NO_SIGN_DATA);
			if (!bySocket) {
				throw new SocketOperationException(ProtocolInvocationLauncherErrorManager.ERROR_NO_SIGN_DATA);
			}
			return ProtocolInvocationLauncherErrorManager
					.getErrorMessage(ProtocolInvocationLauncherErrorManager.ERROR_NO_SIGN_DATA);
		}
		catch (final BadPdfPasswordException e) {
			LOGGER.log(Level.SEVERE, "Error al realizar la operacion de firma: " + e, e); //$NON-NLS-1$
			ProtocolInvocationLauncherErrorManager.showError(ProtocolInvocationLauncherErrorManager.ERROR_PDF_WRONG_PASSWORD);
			if (!bySocket) {
				throw new SocketOperationException(ProtocolInvocationLauncherErrorManager.ERROR_PDF_WRONG_PASSWORD);
			}
			return ProtocolInvocationLauncherErrorManager
					.getErrorMessage(ProtocolInvocationLauncherErrorManager.ERROR_PDF_WRONG_PASSWORD);
		}
		catch (final PdfHasUnregisteredSignaturesException e) {
			LOGGER.log(Level.SEVERE, "Error al realizar la operacion de firma: " + e, e); //$NON-NLS-1$
			ProtocolInvocationLauncherErrorManager.showError(ProtocolInvocationLauncherErrorManager.ERROR_PDF_UNREG_SIGN);
			if (!bySocket) {
				throw new SocketOperationException(ProtocolInvocationLauncherErrorManager.ERROR_PDF_UNREG_SIGN);
			}
			return ProtocolInvocationLauncherErrorManager
					.getErrorMessage(ProtocolInvocationLauncherErrorManager.ERROR_PDF_UNREG_SIGN);
		}
		catch (final PdfIsCertifiedException e) {
			LOGGER.log(Level.SEVERE, "Error al realizar la operacion de firma: " + e, e); //$NON-NLS-1$
			ProtocolInvocationLauncherErrorManager.showError(ProtocolInvocationLauncherErrorManager.ERROR_PDF_CERTIFIED);
			if (!bySocket) {
				throw new SocketOperationException(ProtocolInvocationLauncherErrorManager.ERROR_PDF_CERTIFIED);
			}
			return ProtocolInvocationLauncherErrorManager
					.getErrorMessage(ProtocolInvocationLauncherErrorManager.ERROR_PDF_CERTIFIED);
		}
		catch (final PdfIsPasswordProtectedException e) {
			LOGGER.log(Level.SEVERE, "Error al realizar la operacion de firma: " + e, e); //$NON-NLS-1$
			ProtocolInvocationLauncherErrorManager.showError(ProtocolInvocationLauncherErrorManager.ERROR_PDF_WRONG_PASSWORD);
			if (!bySocket) {
				throw new SocketOperationException(ProtocolInvocationLauncherErrorManager.ERROR_PDF_WRONG_PASSWORD);
			}
			return ProtocolInvocationLauncherErrorManager
					.getErrorMessage(ProtocolInvocationLauncherErrorManager.ERROR_PDF_WRONG_PASSWORD);
		}
		catch (final AOException e) {
			LOGGER.log(Level.SEVERE, "Error al realizar la operacion de firma: " + e, e); //$NON-NLS-1$
			ProtocolInvocationLauncherErrorManager.showErrorDetail(
					ProtocolInvocationLauncherErrorManager.ERROR_SIGNATURE_FAILED,
					e.getMessage()
					);
			if (!bySocket) {
				throw new SocketOperationException(ProtocolInvocationLauncherErrorManager.ERROR_SIGNATURE_FAILED);
			}
			return ProtocolInvocationLauncherErrorManager
					.getErrorMessage(ProtocolInvocationLauncherErrorManager.ERROR_SIGNATURE_FAILED);
		}
		catch (final Exception e) {
			LOGGER.log(Level.SEVERE, "Error al realizar la operacion de firma: " + e, e); //$NON-NLS-1$
			ProtocolInvocationLauncherErrorManager.showError(ProtocolInvocationLauncherErrorManager.ERROR_SIGNATURE_FAILED);
			if (!bySocket) {
				throw new SocketOperationException(ProtocolInvocationLauncherErrorManager.ERROR_SIGNATURE_FAILED);
			}
			return ProtocolInvocationLauncherErrorManager
					.getErrorMessage(ProtocolInvocationLauncherErrorManager.ERROR_SIGNATURE_FAILED);
		}

		// Damos la opcion de guardar la firma generada
		final String fileExts = options.getExtraParams().getProperty("filenameSaveExts"); //$NON-NLS-1$

		final String fileDesc = options.getExtraParams().getProperty("filenameSaveDescription", ProtocolMessages.getString("ProtocolLauncher.30")) +  //$NON-NLS-1$//$NON-NLS-2$
			(fileExts == null ? " (*.*)" : String.format(" (*.%1s)", fileExts.replace(",", ",*."))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

		try {
			AOUIFactory.getSaveDataToFile(
				sign,
				ProtocolMessages.getString("ProtocolLauncher.31"), // Titulo del dialogo //$NON-NLS-1$
				options.getExtraParams().getProperty("filenameSaveCurrentDir"), // Directorio de guardado //$NON-NLS-1$
				getFilename(options, inputFilename, signer),
				Collections.singletonList(
					new GenericFileFilter(
						fileExts != null ? fileExts.split(",") : null, // Extensiones de fichero aceptadas //$NON-NLS-1$
						fileDesc // Descripcion del fichero
					)
				),
				null // Padre para la modalidad
			);
		}
		catch (final AOCancelledOperationException e) {
			LOGGER.severe("Operacion cancelada por el usuario: " + e); //$NON-NLS-1$
			if (!bySocket) {
				throw new SocketOperationException(RESULT_CANCEL);
			}
			return RESULT_CANCEL;
		}
		catch (final Exception e) {
			LOGGER.severe("Error en el guardado de datos: " + e); //$NON-NLS-1$
			ProtocolInvocationLauncherErrorManager.showError(
				ProtocolInvocationLauncherErrorManager.ERROR_CANNOT_SAVE_DATA
			);
			if (!bySocket) {
				throw new SocketOperationException(
					ProtocolInvocationLauncherErrorManager.ERROR_SIGNATURE_FAILED
				);
			}
			return ProtocolInvocationLauncherErrorManager.getErrorMessage(
				ProtocolInvocationLauncherErrorManager.ERROR_CANNOT_SAVE_DATA
			);
		}

		// Concatenamos el certificado utilizado para firmar y la firma con un separador
		// para que la pagina pueda recuperar ambos
		byte[] certEncoded;
		try {
			certEncoded = pke.getCertificateChain()[0].getEncoded();
		}
		catch (final CertificateEncodingException e) {
			LOGGER.severe("Error en la decodificacion del certificado de firma: " + e); //$NON-NLS-1$
			ProtocolInvocationLauncherErrorManager.showError(
				ProtocolInvocationLauncherErrorManager.ERROR_DECODING_CERTIFICATE
			);
			if (!bySocket) {
				throw new SocketOperationException(
					ProtocolInvocationLauncherErrorManager.ERROR_DECODING_CERTIFICATE
				);
			}
			return ProtocolInvocationLauncherErrorManager.getErrorMessage(
				ProtocolInvocationLauncherErrorManager.ERROR_DECODING_CERTIFICATE
			);
		}

		// Si tenemos clave de cifrado, ciframos el certificado y la firma
		final StringBuilder dataToSend = new StringBuilder();

		if (options.getDesKey() != null) {
			try {
				// El CipherData devuelve los datos directamente en Base64
				dataToSend.append(CypherDataManager.cipherData(certEncoded, options.getDesKey()));
				dataToSend.append(CERT_SIGNATURE_SEPARATOR);
				dataToSend.append(CypherDataManager.cipherData(sign, options.getDesKey()));

				// A partir del protocolo version 3, si se cargo un fichero, se devuelve el nombre
				if (inputFilename != null && protocolVersion >= 3) {
					dataToSend.append(CERT_SIGNATURE_SEPARATOR);
					dataToSend.append(CypherDataManager.cipherData(buildExtraDataResult(inputFilename)
							.getBytes(StandardCharsets.UTF_8), options.getDesKey()));
				}
			}
			catch (final Exception e) {
				LOGGER.severe("Error en el cifrado de los datos a enviar: " + e); //$NON-NLS-1$
				ProtocolInvocationLauncherErrorManager.showError(
					ProtocolInvocationLauncherErrorManager.ERROR_ENCRIPTING_DATA
				);
				if (!bySocket) {
					throw new SocketOperationException(
						ProtocolInvocationLauncherErrorManager.ERROR_ENCRIPTING_DATA
					);
				}
				return ProtocolInvocationLauncherErrorManager.getErrorMessage(
					ProtocolInvocationLauncherErrorManager.ERROR_ENCRIPTING_DATA
				);
			}
		}
		else {
			LOGGER.warning(
				"Se omite el cifrado de los datos resultantes por no haberse proporcionado una clave de cifrado" //$NON-NLS-1$
			);
			dataToSend.append(Base64.encode(certEncoded, true));
			dataToSend.append(CERT_SIGNATURE_SEPARATOR);
			// Se hace una doble codigicacion Base64, una de los datos y otras del cifrado, que si bien este ultimo
			// no se realiza, si se mantiene la codificacion
			dataToSend.append(Base64.encode(sign, true));

			// A partir del protocolo version 3, si se cargo un fichero, se devuelve el nombre
			if (inputFilename != null && protocolVersion >= 3) {
				dataToSend.append(CERT_SIGNATURE_SEPARATOR);
				dataToSend.append(Base64.encode(buildExtraDataResult(inputFilename)
						.getBytes(StandardCharsets.UTF_8), true));
			}
		}

		if (!bySocket) {
			// Enviamos la firma cifrada al servicio remoto de intercambio y detenemos la espera
			// activa si se encontraba vigente
			synchronized (IntermediateServerUtil.getUniqueSemaphoreInstance()) {
				final Thread waitingThread = ProtocolInvocationLauncher.getActiveWaitingThread();
				if (waitingThread != null) {
					waitingThread.interrupt();
				}
				try {
					IntermediateServerUtil.sendData(dataToSend, options.getStorageServletUrl().toString(), options.getId());
				}
				catch (final Exception e) {
					LOGGER.log(Level.SEVERE, "Error al enviar los datos al servidor", e); //$NON-NLS-1$
					ProtocolInvocationLauncherErrorManager.showError(
							ProtocolInvocationLauncherErrorManager.ERROR_SENDING_SIGNATURE
							);
					return ProtocolInvocationLauncherErrorManager.getErrorMessage(
							ProtocolInvocationLauncherErrorManager.ERROR_SENDING_SIGNATURE
							);
				}
			}
		}
		else {
			LOGGER.info(
				"Se omite el envio por red de los datos resultantes por no haberse proporcionado una URL de destino" //$NON-NLS-1$
			);
		}

		return dataToSend.toString();
	}

	/** Genera un nombre de fichero.
	 * @param options Opciones proporcionadas en la operaci&oacute;n.
	 * @param filename Nombre del fichero firmado ({@code null} si no es conocido).
	 * @param signer Manejador utilizado para la firma-
	 * @return Nombre de fichero por defecto. */
	private static String getFilename(final UrlParametersToSignAndSave options,
			                          final String filename,
			                          final AOSigner signer) {

		if (options.getFileName() != null) {
			return options.getFileName();
		}

		final String name;
		if (filename != null) {
			final int dotPos = filename.lastIndexOf('.');
			if (dotPos > 0) {
				name = filename.substring(0, dotPos);
			}
			else {
				name = filename;
			}
		}
		else {
			name = ProtocolMessages.getString("ProtocolLauncher.30"); //$NON-NLS-1$
		}

		return signer.getSignedName(name, null);
	}

	public static String getResultCancel() {
		return RESULT_CANCEL;
	}

	/** Identifica cuando se ha configurado una firma con el formato XAdES y la
	 * propiedad {@code mode} con el valor {@code explicit}. Esta no es una
	 * firma correcta, pero por compatibilidad con los tipos de firmas del
	 * Applet pesado se ha incluido aqu&iacute;.
	 * @param format Formato declarado para la firma.
	 * @param config Par&aacute;metros adicionales declarados para la firma.
	 * @return {@code true} si se configura una firma <i>XAdES explicit</i>,
	 *         {@code false} en caso contrario.
	 * @deprecated Uso temporal hasta que se elimine el soporte de firmas XAdES
	 *             expl&iacute;citas. */
	@Deprecated
	private static boolean isXadesExplicitConfigurated(final String format, final Properties config) {
		return format != null &&
			   format.toLowerCase().startsWith("xades") && //$NON-NLS-1$
			   config != null &&
			   AOSignConstants.SIGN_MODE_EXPLICIT.equalsIgnoreCase(config.getProperty("mode")); //$NON-NLS-1$
	}

	/**
	 * Construye una cadena de texto con un objeto JSON de datos extra que enviar en la respuesta.
	 * @param filename Nombre de fichero.
	 * @return Cadena con el JSON de datos extra.
	 */
	private static String buildExtraDataResult(final String filename) {
		if (filename == null) {
			return null;
		}
		return "{\"filename\":\"" + filename + "\"}"; //$NON-NLS-1$ //$NON-NLS-2$
	}
}
