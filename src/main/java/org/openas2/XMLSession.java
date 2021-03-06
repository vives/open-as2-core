package org.openas2;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.openas2.cert.CertificateFactory;
import org.openas2.cmd.CommandManager;
import org.openas2.cmd.CommandRegistry;
import org.openas2.cmd.CommandRegistryFactory;
import org.openas2.cmd.processor.BaseCommandProcessor;
import org.openas2.partner.PartnershipFactory;
import org.openas2.processor.Processor;
import org.openas2.processor.ProcessorModule;
import org.openas2.util.XMLUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * original author unknown
 * 
 * in this release added command registry methods
 * @author joseph mcverry
 */
public class XMLSession extends BaseSession implements CommandRegistryFactory 
{
	public static final String EL_CERTIFICATES = "certificates";
	
	public static final String EL_CMDPROCESSOR = "commandProcessors";
	
	public static final String EL_PROCESSOR = "processor";
	
	public static final String EL_PARTNERSHIPS = "partnerships";
	
	public static final String EL_COMMANDS = "commands";
	
	public static final String EL_LOGGERS = "loggers";
	
	public static final String PARAM_BASE_DIRECTORY = "basedir";

	/** Logger for the class. */
	private static final Logger LOGGER = LoggerFactory.getLogger(XMLSession.class);

	private CommandRegistry commandRegistry;
	
	private String baseDirectory = ".";
	
	private CommandManager cmdManager;

	
	public XMLSession(String content) throws OpenAS2Exception,
	ParserConfigurationException, SAXException, IOException 
	{
		this(IOUtils.toInputStream(content, "UTF-8"));
	}

	
	public XMLSession(InputStream in) throws OpenAS2Exception,
			ParserConfigurationException, SAXException, IOException
	{
		this(in, null);
	}

	public XMLSession(InputStream in, String baseDirectory) throws OpenAS2Exception,
			ParserConfigurationException, SAXException, IOException
	{
		super();

		if (StringUtils.isNotEmpty(baseDirectory))
		{
			this.baseDirectory = baseDirectory;
		}
		
		load(in);
	}


	public void setCommandRegistry(CommandRegistry registry)
	{
		commandRegistry = registry;
	}

	@Override
	public CommandRegistry getCommandRegistry()
	{
		return commandRegistry;
	}

	protected void load(InputStream in) throws ParserConfigurationException,
			SAXException, IOException, OpenAS2Exception
	{
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

		DocumentBuilder parser = factory.newDocumentBuilder();
		Document document = parser.parse(in);
		Element root = document.getDocumentElement();

		NodeList rootNodes = root.getChildNodes();
		Node rootNode;
		String nodeName;

		for (int i = 0; i < rootNodes.getLength(); i++)
		{
			rootNode = rootNodes.item(i);

			nodeName = rootNode.getNodeName();

			if (nodeName.equals(EL_CERTIFICATES))
			{
				loadCertificates(rootNode);
			}
			else if (nodeName.equals(EL_PROCESSOR))
			{
				loadProcessor(rootNode);
			}
			else if (nodeName.equals(EL_CMDPROCESSOR))
			{
				loadCommandProcessors(rootNode);
			}
			else if (nodeName.equals(EL_PARTNERSHIPS))
			{
				loadPartnerships(rootNode);
			}
			else if (nodeName.equals(EL_COMMANDS))
			{
				loadCommands(rootNode);
			}
			else if (nodeName.equals(EL_LOGGERS))
			{
				loadAppender(rootNode);
			}
			else if (nodeName.equals("#text") || nodeName.equals("#comment"))
			{
				// do nothing
			}
			else
			{
				throw new OpenAS2Exception("Undefined tag: " + nodeName);
			}
		}
	}

	protected void loadCertificates(Node rootNode) throws OpenAS2Exception
	{
		CertificateFactory certFx = (CertificateFactory) XMLUtil.getComponent(
				rootNode, this);
		setComponent(CertificateFactory.COMPID_CERTIFICATE_FACTORY, certFx);
	}

	protected void loadCommands(Node rootNode) throws OpenAS2Exception
	{
		CommandRegistry cmdReg = (CommandRegistry) XMLUtil.getComponent(
				rootNode, this);
		setCommandRegistry(cmdReg);
	}

	protected void loadCommandProcessors(Node rootNode) throws OpenAS2Exception
	{
		cmdManager = CommandManager.getCmdManager();

		NodeList cmdProcessor = rootNode.getChildNodes();
		Node processor;

		for (int i = 0; i < cmdProcessor.getLength(); i++)
		{
			processor = cmdProcessor.item(i);

			if (processor.getNodeName().equals("commandProcessor"))
			{
				loadCommandProcessor(cmdManager, processor);
			}
		}
	}

	public CommandManager getCommandManager()
	{
		return cmdManager;
	}

	protected void loadCommandProcessor(CommandManager manager,
			Node cmdPrcessorNode) throws OpenAS2Exception
	{
		BaseCommandProcessor cmdProcesor = (BaseCommandProcessor) XMLUtil
				.getComponent(cmdPrcessorNode, this);
		manager.addProcessor(cmdProcesor);
	}

	protected void loadPartnerships(Node rootNode) throws OpenAS2Exception
	{
		PartnershipFactory partnerFx = (PartnershipFactory) XMLUtil
				.getComponent(rootNode, this);
		setComponent(PartnershipFactory.COMPID_PARTNERSHIP_FACTORY, partnerFx);
	}

	private void loadAppender(Node node)
	{
		NodeList childNodes = node.getChildNodes();

		for (int i = 0; i < childNodes.getLength(); i++)
		{
			Node nodeLogger = childNodes.item(i);
			if (nodeLogger.getNodeName().equals("logger"))
			{
				Map mapAttributes = XMLUtil.mapAttributes(nodeLogger);

				if (mapAttributes.containsKey("classname"))
				{
					LOGGER.warn(
							"The appender with the class name: {} will be ignored. "
									+ "Please, use your logback configuration file to custom the logs...",
							mapAttributes.get("classname"));
				}
			}
		}
	}

	protected void loadProcessor(Node rootNode) throws OpenAS2Exception
	{
		Processor proc = (Processor) XMLUtil.getComponent(rootNode, this);
		setComponent(Processor.COMPID_PROCESSOR, proc);

		NodeList modules = rootNode.getChildNodes();
		Node module;

		for (int i = 0; i < modules.getLength(); i++)
		{
			module = modules.item(i);

			if (module.getNodeName().equals("module"))
			{
				loadProcessorModule(proc, module);
			}
		}
	}

	protected void loadProcessorModule(Processor proc, Node moduleNode)
			throws OpenAS2Exception
	{
		ProcessorModule procmod = (ProcessorModule) XMLUtil.getComponent(
				moduleNode, this);
		proc.getModules().add(procmod);
	}

	public String getBaseDirectory()
	{
		return baseDirectory;
	}

	public void setBaseDirectory(String dir)
	{
		baseDirectory = dir;
	}
}
