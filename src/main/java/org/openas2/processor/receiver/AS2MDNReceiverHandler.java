package org.openas2.processor.receiver;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

import javax.activation.DataHandler;
import javax.mail.MessagingException;
import javax.mail.internet.ContentType;
import javax.mail.internet.InternetHeaders;
import javax.mail.internet.MimeBodyPart;

import org.openas2.DispositionException;
import org.openas2.OpenAS2Exception;
import org.openas2.WrappedException;
import org.openas2.cert.CertificateFactory;
import org.openas2.message.AS2Message;
import org.openas2.message.AS2MessageMDN;
import org.openas2.message.MessageMDN;
import org.openas2.message.NetAttribute;
import org.openas2.partner.AS2Partnership;
import org.openas2.partner.Partnership;
import org.openas2.processor.storage.StorageModule;
import org.openas2.util.AS2UtilOld;
import org.openas2.util.ByteArrayDataSource;
import org.openas2.util.DispositionType;
import org.openas2.util.HTTPUtil;
import org.openas2.util.IOUtilOld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AS2MDNReceiverHandler implements NetModuleHandler
{
	/** Logger for the class. */
	private static final Logger LOGGER = LoggerFactory.getLogger(AS2MDNReceiverHandler.class);

	private AS2MDNReceiverModule module;

	public AS2MDNReceiverHandler(AS2MDNReceiverModule module)
	{
		super();
		this.module = module;
	}

	public String getClientInfo(InetAddress remoteIp, int remotePort)
	{
		return " " + remoteIp.getHostAddress() + " " + remotePort;
	}

	public AS2MDNReceiverModule getModule()
	{
		return module;
	}

	@Override
	public void handle(NetModule owner, Socket s)
	{
		InputStream inputStream = null;
		OutputStream outputStream = null;

		try
		{
			inputStream = s.getInputStream();
			outputStream = s.getOutputStream();
		}
		catch (IOException ioe)
		{
			AS2Message msg = createMessage(s.getInetAddress(), s.getPort(), s.getLocalAddress(), s.getLocalPort());

			DispositionType dispositionType = new DispositionType("automatic-action", "failed to send MDN",
					"processed", "Error", "unexpected-processing-error");

			DispositionException dispositionException = new DispositionException(dispositionType,
					"internal error occured to send MDN", ioe);

			getModule().handleError(msg, dispositionException);
			return;
		}

		handle(s.getInetAddress(), s.getPort(), s.getLocalAddress(), s.getLocalPort(), inputStream, outputStream);
	}

	@Override
	public void handle(final InetAddress remoteIp, final int remotePort, final InetAddress localIp,
			final int localPort, final InputStream inputStream, final OutputStream outputStream)
	{
		LOGGER.info("incoming connection  [{}]", getClientInfo(remoteIp, remotePort));

		AS2Message msg = new AS2Message();

		byte[] data = null;

		// Read in the message request, headers, and data
		try
		{
			data = readMessage(inputStream, outputStream, msg);

			// Asynch MDN 2007-03-12
			// check if the requested URL is defined in attribute "as2_receipt_option"
			// in one of partnerships, if yes, then process incoming AsyncMDN
			LOGGER.info("incoming connection for receiving AsyncMDN [{}] {}", getClientInfo(remoteIp, remotePort),
					msg.getLoggingText());
			ContentType receivedContentType;

			MimeBodyPart receivedPart = new MimeBodyPart(msg.getHeaders(), data);
			msg.setData(receivedPart);
			receivedContentType = new ContentType(receivedPart.getContentType());

			receivedContentType = new ContentType(msg.getHeader("Content-Type"));

			receivedPart.setDataHandler(new DataHandler(new ByteArrayDataSource(data, receivedContentType
					.toString(), null)));
			receivedPart.setHeader("Content-Type", receivedContentType.toString());

			msg.setData(receivedPart);

			receiveMDN(msg, data, outputStream);

		}
		catch (Exception e)
		{
			NetException ne = new NetException(remoteIp, remotePort, e);
			ne.terminate();
		}
	}

	protected byte[] readMessage(final InputStream inputStream, final OutputStream outputStream, AS2Message msg)
			throws IOException, MessagingException
	{
		return HTTPUtil.readData(inputStream, outputStream, msg);
	}

	// Asynch MDN 2007-03-12
	/**
	 * Method for receiving &amp; processing Async MDN sent from receiver.
	 * 
	 * @param msg as2 message.
	 * @param data byte array received.
	 * @param out stream to send response.
	 * @throws OpenAS2Exception exception
	 * @throws IOException exception
	 */
	protected void receiveMDN(AS2Message msg, byte[] data, OutputStream out)
			throws OpenAS2Exception, IOException
	{
		try
		{
			// create a MessageMDN and copy HTTP headers
			MessageMDN mdn = new AS2MessageMDN(msg);
			// copy headers from msg to MDN from msg
			mdn.setHeaders(msg.getHeaders());
			MimeBodyPart part = new MimeBodyPart(mdn.getHeaders(), data);
			msg.getMDN().setData(part);

			// get the MDN partnership info
			mdn.getPartnership().setSenderID(AS2Partnership.PID_AS2, mdn.getHeader("AS2-From"));
			mdn.getPartnership().setReceiverID(AS2Partnership.PID_AS2, mdn.getHeader("AS2-To"));
			getModule().getSession().getPartnershipFactory().updatePartnership(mdn, false);

			CertificateFactory cFx = getModule().getSession().getCertificateFactory();
			X509Certificate senderCert = cFx.getCertificate(mdn, Partnership.PTYPE_SENDER);

			AS2UtilOld.parseMDN(msg, senderCert);

			// in order to name & save the mdn with the original AS2-From + AS2-To + Message id.,
			// the 3 msg attributes have to be reset before calling MDNFileModule
			msg.getPartnership().setReceiverID(AS2Partnership.PID_AS2, mdn.getHeader("AS2-From"));
			msg.getPartnership().setSenderID(AS2Partnership.PID_AS2, mdn.getHeader("AS2-To"));
			getModule().getSession().getPartnershipFactory().updatePartnership(msg, false);
			msg.setMessageID(msg.getMDN().getAttribute(AS2MessageMDN.MDNA_ORIG_MESSAGEID));
			getModule().getSession().getProcessor().handle(StorageModule.DO_STOREMDN, msg, null);

			// check if the mic (message integrity check) is correct
			if (checkAsyncMDN(msg))
			{
				HTTPUtil.sendHTTPResponse(out, HttpURLConnection.HTTP_OK, false);
			}
			else
			{
				HTTPUtil.sendHTTPResponse(out, HttpURLConnection.HTTP_NOT_FOUND, false);
			}

			String disposition = msg.getMDN().getAttribute(
					AS2MessageMDN.MDNA_DISPOSITION);
			try
			{
				new DispositionType(disposition).validate();
			}
			catch (DispositionException de)
			{
				de.setText(msg.getMDN().getText());

				if ((de.getDisposition() != null) && de.getDisposition().isWarning())
				{
					de.addSource(OpenAS2Exception.SOURCE_MESSAGE, msg);
					de.terminate();
				}
				else
				{
					throw de;
				}
			}
		}
		catch (Exception e)
		{
			HTTPUtil.sendHTTPResponse(out, HttpURLConnection.HTTP_BAD_REQUEST, false);
			if (e instanceof IOException)
			{
				throw (IOException)e;
			}
			WrappedException we = new WrappedException(e);
			we.addSource(OpenAS2Exception.SOURCE_MESSAGE, msg);
			throw we;
		}
	}

	// Asynch MDN 2007-03-12
	/**
	 * verify if the mic is matched.
	 * 
	 * @param msg as2 message.
	 * @return true if mdn processed
	 */
	public boolean checkAsyncMDN(AS2Message msg)
	{
		try
		{
			// get the returned mic from mdn object
			String returnmic = msg.getMDN().getAttribute(AS2MessageMDN.MDNA_MIC);

			// use original message id. to open the pending information file
			// from pendinginfo
			// folder.
			String ORIG_MESSAGEID = msg.getMDN().getAttribute(
					AS2MessageMDN.MDNA_ORIG_MESSAGEID);
			String pendinginfofile = (String)this.getModule().getSession().getComponent("processor").getParameters()
					.get("pendingmdninfo")
					+ "/"
					+ ORIG_MESSAGEID.substring(1, ORIG_MESSAGEID.length() - 1);
			BufferedReader pendinginfo = new BufferedReader(new FileReader(
					pendinginfofile));

			// Get the original mic from the first line of pending information
			// file
			String originalmic = pendinginfo.readLine();

			// Get the original pending file from the second line of pending
			// information file
			File fpendingfile = new File(pendinginfo.readLine());
			pendinginfo.close();

			String disposition = msg.getMDN().getAttribute(
					AS2MessageMDN.MDNA_DISPOSITION);

			LOGGER.info("received MDN [{}] {}", disposition, msg.getLoggingText());
			// original code just did string compare - returnmic.equals(originalmic). Sadly this is
			// not good enough as the mic fields are "base64string, algorith" taken from a rfc822 style
			// Returned-Content-MIC header and rfc822 headers can contain spaces all over the place.
			// (not to mention comments!). Simple fix - delete all spaces.
			if (!returnmic.replaceAll("\\s+", "").equals(originalmic.replaceAll("\\s+", "")))
			{
				LOGGER.info("mic not matched, original mic: {} return mic: {}{}", originalmic, returnmic,
						msg.getLoggingText());
				return false;
			}

			// delete the pendinginfo & pending file if mic is matched
			LOGGER.info("mic is matched, mic: {}{}", returnmic, msg.getLoggingText());
			File fpendinginfofile = new File(pendinginfofile);
			LOGGER.info("delete pendinginfo file : {} from pending folder : {}{}", fpendinginfofile.getName(),
					(String)this.getModule().getSession().getComponent("processor")
							.getParameters().get("pendingmdn"), msg.getLoggingText());

			fpendinginfofile.delete();

			LOGGER.info("delete pending file : {} from pending folder : {}{}", fpendingfile.getName(),
					fpendingfile.getParent(), msg.getLoggingText());
			fpendingfile.delete();
		}
		catch (Exception e)
		{
			LOGGER.error(e.getMessage(), e);
			return false;
		}

		return true;
	}

	// Copy headers from an Http connection to an InternetHeaders object
	protected void copyHttpHeaders(HttpURLConnection conn, InternetHeaders headers)
	{
		Iterator connHeadersIt = conn.getHeaderFields().entrySet().iterator();
		Iterator connValuesIt;
		Map.Entry connHeader;
		String headerName;

		while (connHeadersIt.hasNext())
		{
			connHeader = (Map.Entry)connHeadersIt.next();
			headerName = (String)connHeader.getKey();

			if (headerName != null)
			{
				connValuesIt = ((Collection)connHeader.getValue()).iterator();

				while (connValuesIt.hasNext())
				{
					String value = (String)connValuesIt.next();

					if (headers.getHeader(headerName) == null)
					{
						headers.setHeader(headerName, value);
					}
					else
					{
						headers.addHeader(headerName, value);
					}
				}
			}
		}
	}

	public void reparse(AS2Message msg, HttpURLConnection conn)
	{
		// Create a MessageMDN and copy HTTP headers
		MessageMDN mdn = new AS2MessageMDN(msg);
		copyHttpHeaders(conn, mdn.getHeaders());

		// Receive the MDN data
		ByteArrayOutputStream mdnStream = null;
		try
		{
			InputStream connIn = conn.getInputStream();
			mdnStream = new ByteArrayOutputStream();

			// Retrieve the message content
			if (mdn.getHeader("Content-Length") != null)
			{
				try
				{
					int contentSize = Integer.parseInt(mdn.getHeader("Content-Length"));

					IOUtilOld.copy(connIn, mdnStream, contentSize);
				}
				catch (NumberFormatException nfe)
				{
					IOUtilOld.copy(connIn, mdnStream);
				}
			}
			else
			{
				IOUtilOld.copy(connIn, mdnStream);
			}
			connIn.close();

		}
		catch (IOException ioe)
		{
			LOGGER.error(ioe.getMessage(), ioe);
		}

		MimeBodyPart part = null;
		try
		{
			part = new MimeBodyPart(mdn.getHeaders(), mdnStream == null ? new byte[0] : mdnStream.toByteArray());
		}
		catch (MessagingException e)
		{
			e.printStackTrace();
		}

		msg.getMDN().setData(part);

		// get the MDN partnership info
		mdn.getPartnership().setSenderID(AS2Partnership.PID_AS2, mdn.getHeader("AS2-From"));
		mdn.getPartnership().setReceiverID(AS2Partnership.PID_AS2, mdn.getHeader("AS2-To"));
	}

	// Create a new message and record the source ip and port
	protected AS2Message createMessage(InetAddress remoteIp, int remotePort, InetAddress localIp, int localPort)
	{
		AS2Message msg = new AS2Message();

		msg.setAttribute(NetAttribute.MA_SOURCE_IP, localIp.toString());
		msg.setAttribute(NetAttribute.MA_SOURCE_PORT, remotePort + "");
		msg.setAttribute(NetAttribute.MA_DESTINATION_IP, remoteIp.toString());
		msg.setAttribute(NetAttribute.MA_DESTINATION_PORT, localPort + "");

		return msg;
	}

}
