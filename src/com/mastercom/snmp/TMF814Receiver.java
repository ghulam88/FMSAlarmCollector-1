package com.mastercom.snmp;

import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.Properties;
import java.util.Vector;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.log4j.DailyRollingFileAppender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.json.simple.JSONObject;
import org.snmp4j.CommandResponder;
import org.snmp4j.CommandResponderEvent;
import org.snmp4j.CommunityTarget;
import org.snmp4j.MessageDispatcherImpl;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.TransportMapping;
import org.snmp4j.mp.MPv2c;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.security.SecurityModels;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.USM;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.GenericAddress;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TcpAddress;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultTcpTransportMapping;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import org.snmp4j.util.MultiThreadedMessageDispatcher;
import org.snmp4j.util.ThreadPool;

public class TMF814Receiver implements CommandResponder {

	private static final Logger logger = Logger.getLogger(TMF814Receiver.class.getName());
	private MultiThreadedMessageDispatcher dispatcher;
	private static KafkaProducer<String, String> producer = null;
	private Snmp snmp = null;
	private Address listenAddress;
	private ThreadPool threadPool;
	private static String emsName;
	private static String emsType;
	static int trapCounter = 0;
	static BufferedWriter writer = null;

	public TMF814Receiver() {

	}

	public static void main(String[] args) {

		try {
			//emsName = args[0];
			//emsType = args[1];			
			initLog4J(); // Initializes log4J logger library.
			initKafka(); // Initializes Kafka
			logger.info("Trap receiving program started for " + emsName);
			//logger.info("EMS Name: " + emsName);
			//logger.info("EMS Type: " + emsType);
			new TMF814Receiver().run();
			//System.out.println("receiver started for :" + emsName);
		} catch (Exception ex) {
			ex.printStackTrace();
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			ex.printStackTrace(pw);
			String errorString = sw.toString();
			logger.info(errorString);
		}
	}

	/**
	 * run method will be called once from the main method. It is starting point of
	 * trap receiver program.
	 */
	private void run() {
		try {
			init();
			snmp.addCommandResponder(this);
		} catch (Exception ex) {
			ex.printStackTrace();
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			ex.printStackTrace(pw);
			String errorString = sw.toString();
			logger.info(errorString);
		}
	}

	/**
	 * Initializes trap receiver program and make it ready to receive trap from the
	 * device.
	 */
	private void init() {

		try {
			// Creating pool of threads to receive traps.
			threadPool = ThreadPool.create("Trap", 25);
			dispatcher = new MultiThreadedMessageDispatcher(threadPool, new MessageDispatcherImpl());
			// Set port address on which trap will be received from the device.
			listenAddress = GenericAddress.parse(System.getProperty("snmp4j.listenAddress", "udp:0.0.0.0/162"));
			TransportMapping transport;

			if (listenAddress instanceof UdpAddress) {
				transport = new DefaultUdpTransportMapping((UdpAddress) listenAddress);
			} else {
				transport = new DefaultTcpTransportMapping((TcpAddress) listenAddress);
			}

			USM usm = new USM(SecurityProtocols.getInstance(), new OctetString(MPv3.createLocalEngineID()), 0);
			SecurityModels.getInstance().addSecurityModel(usm);

			snmp = new Snmp(dispatcher, transport);
			snmp.getMessageDispatcher().addMessageProcessingModel(new MPv2c());
			snmp.listen();
			logger.info("waiting for traps......");

		} catch (Exception ex) {
			ex.printStackTrace();
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			ex.printStackTrace(pw);
			String errorString = sw.toString();
			logger.info(errorString);
		}
	}

	/**
	 * This method will be called by the program whenever a trap is received. For
	 * each and every traps this method will be called.
	 */
	@Override
	public synchronized void processPdu(CommandResponderEvent event) {

		try {
			JSONObject trapObject = new JSONObject();
			trapCounter++;
			logger.info("Received trap Count:" + trapCounter);
			logger.info("Original trap:" + event.toString());
			System.out.println("Trap received: " + trapCounter);
			String peerAddr = event.getPeerAddress().toString();
			String peerAddrArr[] = peerAddr.split("/");

			String sourceDeviceIp = peerAddrArr[0];
			
			Vector<? extends VariableBinding> varBinds = event.getPDU().getVariableBindings();
			VariableBinding[] bindings = new VariableBinding[varBinds.size()];		
			int i = 0;
			if (varBinds != null && !varBinds.isEmpty()) {
				Iterator<? extends VariableBinding> varIter = varBinds.iterator();
				while (varIter.hasNext()) {
					VariableBinding var = varIter.next();
					Variable v = var.getVariable();
					bindings[i] = var;
					i++;
				}
			}			
			
			
			//this.sendToSevOne(sourceDeviceIp, "192.168.4.7", "1621", bindings);
			
			JSONObject trapObj = new JSONObject();
			trapObj.put("emsName", "ems");
			trapObj.put("emsType", "emstype");
			trapObj.put("notificationTime", new Date().getTime());
			trapObj.put("rawTrap", event.toString());

			logger.info("Writing to file and pushing to Kafka: " + trapObj.toJSONString());
			System.out.println("Writing to Kafka");
			PushMessageToKafka("tmf", trapCounter + "", trapObj.toJSONString());
			System.out.println("Written to Kafka");
//			writer.append(event.toString());
//			writer.newLine();
//			writer.flush();

		} catch (Exception ex) {
			ex.printStackTrace();
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			ex.printStackTrace(pw);
			String errorString = sw.toString();
			logger.info(errorString);
		}
	}

	public static void initKafka() throws Exception {

		Properties props = new Properties();
		props.put("bootstrap.servers", "localhost:9092");
		//props.put("bootstrap.servers", "192.168.4.33:9092,192.168.4.33:9093");
		props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
		props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
		props.put("enable.idempotence", true);
		props.put("acks", "all");
		props.put("retries", 5);
		props.put("batch.size", 16384);
		props.put("linger.ms", 1);

		producer = new KafkaProducer<>(props);
		

	}

	public static void PushMessageToKafka(String topicName, String key, String value) {
		try {
			ProducerRecord<String, String> record = new ProducerRecord<>(topicName, key, value);
			producer.send(record);
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	/**
	 * Initialize the logger. Logger keep on logging all the logs of the program.
	 */
	private static void initLog4J() {
		try {
			PatternLayout layout = new PatternLayout();
			String conversionPattern = "[%p] %d %c %M - %m%n";
			layout.setConversionPattern(conversionPattern);

			// creates daily rolling file appender
			DailyRollingFileAppender rollingAppender = new DailyRollingFileAppender();
			// rollingAppender.setFile("D:\\alltraps.log"); // Daily
			rollingAppender.setFile("/home/AlarmReceiverAdapter/alltraps.log"); // Daily
			rollingAppender.setDatePattern("'.'yyyy-MM-dd"); // Pattern of daily log
																// file.
			rollingAppender.setLayout(layout);
			rollingAppender.activateOptions();

			// configures the root logger
			Logger rootLogger = Logger.getRootLogger();
			rootLogger.setLevel(Level.DEBUG);
			rootLogger.addAppender(rollingAppender);
		} catch (Exception ex) {
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			ex.printStackTrace(pw);
			String errorString = sw.toString();
			logger.info(errorString);
		}
	}

	public static void readAppConfig() {
		Properties prop = new Properties();
		try {
			prop.load(new FileInputStream("/home/AlarmReceiverAdapter/appconfig.cfg"));
			String value = prop.getProperty("key");
			System.out.println("Value: " + value);
		} catch (IOException ex) {
			ex.printStackTrace();
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			ex.printStackTrace(pw);
			String errorString = sw.toString();
			logger.info(errorString);
		}
	}
	
	 private void sendToSevOne(String sourceIp, String vendorIP, String vendorPort, VariableBinding[] bindings) {
			enableIPTables(sourceIp, vendorIP, vendorPort);
			ForwardTrap2SevOne(vendorIP, vendorPort, bindings, sourceIp);
			disableIPTables(sourceIp, vendorIP, vendorPort);
		   }
		   
		   public void ForwardTrap2SevOne(String destinationIPVal, String destinationPortVal,
					VariableBinding[] trapBindingsList, String sourceDeviceIp) {

				try {

					// Create Transport Mapping
					TransportMapping transport = new DefaultUdpTransportMapping();
					transport.listen();
					String community = "public";

					// Create Target for V2 trap
					CommunityTarget cTarget = new CommunityTarget();
					cTarget.setCommunity(new OctetString(community));
					cTarget.setVersion(SnmpConstants.version2c);
					cTarget.setAddress(new UdpAddress(destinationIPVal + "/" + destinationPortVal));
					cTarget.setRetries(3);
					cTarget.setTimeout(5000);

					// Create PDU for V2
					PDU pdu = new PDU();

					// Add all bindings which was received from trap.
					pdu.addAll(trapBindingsList);
					//sevOneForwardCount++;
					logger.info("========Sending trap to SevOne IP : "+destinationIPVal+" POrt : "+destinationPortVal);
					//logger.info("SevOne trap count=" + sevOneForwardCount);
					logger.info("========Sending trap to SevOne with PDU=======================" + pdu.toString());

					// Send the PDU
					pdu.setType(PDU.TRAP);
					Snmp snmp = new Snmp(transport);
					snmp.send(pdu, cTarget);
					snmp.close();

				} catch (Exception ex) {
					StringWriter sw = new StringWriter();
					PrintWriter pw = new PrintWriter(sw);
					ex.printStackTrace(pw);

					String errorString = sw.toString();
					logger.info(errorString);
				}

			}
		   
			private void enableIPTables(String sourceIP, String destinationIP, String destinationPort) {

				// iptables -t nat -A POSTROUTING -d $TRAP_RECEIVER -p udp --dport 162
				// -j SNAT --to $SRC
				String command = "iptables -t nat -A POSTROUTING -d " + destinationIP + " -p udp --dport " + destinationPort
						+ " -j SNAT --to " + sourceIP;
				
				logger.info(" iptable insert cmd =" + command);

				Process p;
				try {

					p = Runtime.getRuntime().exec(new String[] { "/bin/bash", "-c", command });
					p.waitFor();

				} catch (Exception ex) {
					StringWriter sw = new StringWriter();
					PrintWriter pw = new PrintWriter(sw);
					ex.printStackTrace(pw);

					String errorString = sw.toString();
					logger.info(errorString);
				}
			}

			/**
			 * This method is for removing entry from linux iptables. For SevOne entry
			 * in iptable will be made and removed afterwards.
			 * 
			 * @param sourceIP
			 * @param destinationIP
			 * @param destinationPort
			 */
			private void disableIPTables(String sourceIP, String destinationIP, String destinationPort) {

				// iptables -t nat -D POSTROUTING -d ${TRAP_RECEIVER} -p udp --dport 162
				// -j SNAT --to $SRC
				String command = "iptables -t nat -D POSTROUTING -d " + destinationIP + " -p udp --dport " + destinationPort
						+ " -j SNAT --to " + sourceIP;
				logger.info(" iptable cmd =" + command);

				Process p;
				try {
					p = Runtime.getRuntime().exec(new String[] { "/bin/bash", "-c", command });
					p.waitFor();
				} catch (Exception ex) {
					StringWriter sw = new StringWriter();
					PrintWriter pw = new PrintWriter(sw);
					ex.printStackTrace(pw);

					String errorString = sw.toString();
					logger.info(errorString);
				}

			}
}