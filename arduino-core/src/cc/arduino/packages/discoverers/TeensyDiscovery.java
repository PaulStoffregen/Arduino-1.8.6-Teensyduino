package cc.arduino.packages.discoverers;

import cc.arduino.packages.BoardPort;
import cc.arduino.packages.Discovery;
import processing.app.BaseNoGui;

import java.util.LinkedList;
import java.util.List;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;


public class TeensyDiscovery implements Discovery {
	private final List<BoardPort> portlist;
	private Process program=null;
	private JsonParser parser;
	private ObjectMapper mapper;

	public TeensyDiscovery() {
		portlist = new LinkedList<>();
	}

	@Override
	public void run() {
		String cmdline = BaseNoGui.getHardwarePath() + File.separator
			+ "tools" + File.separator + "teensy_ports";
		try {
			program = Runtime.getRuntime().exec(new String[] {cmdline, "-J"});
		} catch (Exception e1) {
			program = null;
		}
		if (program == null) {
			print("unable to run teensy_ports -J");
			return;
		}
		InputStream input = program.getInputStream();
		print("teensy_ports -J started");
		try {
			// https://sohlich.github.io/post/jackson/
			// https://www.baeldung.com/jackson-object-mapper-tutorial
			JsonFactory factory = new JsonFactory();
			parser = factory.createJsonParser(input);
			mapper = new ObjectMapper();
			mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
			while (true) {
				BoardPort port = mapper.readValue(parser, BoardPort.class);
				if (port != null) {
					incoming(port);
				}
			}
		} catch (Exception e) {
			print("ended with exception");
		}
		print("end");
	}

	private synchronized void incoming(BoardPort port) {
		String address = port.getAddress();
		if (address == null) {
			return; // address is required
		}
		for (BoardPort bp : portlist) {
			if (address.equals(bp.getAddress())) {
				// if address already on the list, discard old info
				portlist.remove(bp);
			}
		}
		if (port.isOnline()) {
			if (port.getLabel() == null) {
				// if no label, use address
				port.setLabel(address);
			}
			if (port.getProtocol() == null) {
				// if no protocol, assume serial
				port.setProtocol("serial");
			}
			portlist.add(new BoardPort(port));
		}
	}

	@Override
	public void start() {
		print("start");
		run();
	}

	@Override
	public void stop() {
		print("stop");
		if (program != null) {
			program.destroy();
			program = null;
		}
	}

	@Override
	public List<BoardPort> listDiscoveredBoards() {
		return listDiscoveredBoards(false);
	}

	@Override
	public synchronized List<BoardPort> listDiscoveredBoards(boolean complete) {
		print("listDiscoveredBoards");
		List<BoardPort> portlistCopy = new LinkedList<>();
		for (BoardPort bp : portlist) {
			portlistCopy.add(new BoardPort(bp));
		}
		return portlistCopy;
	}

	void print(String str) {
		System.err.println("TeensyDiscovery: " + str);
	}
}


/*
public class TeensyDiscovery implements Discovery {

	Socket sock=null;
	InputStream input;
	OutputStream output;
	static Process program=null;

	@Override
	public void run() {
		start();
	}

	@Override
	public void start() {
		print("start");
		if (!connect()) {
			if (run_program()) {
				connect();
			}
		}
	}

	@Override
	public void stop() {
		print("stop");
		close();
	}

	@Override
	public List<BoardPort> listDiscoveredBoards() {
		return listDiscoveredBoards(false);
	}

	@Override
	public List<BoardPort> listDiscoveredBoards(boolean complete) {
		print("listDiscoveredBoards");
		List<BoardPort> ports = new LinkedList<>();

		for (int attempt=0; attempt < 5; attempt++) {
			if (sock != null && !sock.isConnected()) {
				print("sock not connected, setting null");
				sock = null;
			}
			if (sock == null) {
				print("sock try reconnect");
				if (!connect()) {
					print("sock try run program & reconnect");
					if (!run_program()) {
						print("Unable to run teensy_ports");
						break;
					}
					for (int n=1; n < 100; n++) {
						if (connect()) {
							print("reconnect on try #" + n);
							break;
						}
						try {
							Thread.sleep(5);
						} catch (Exception e) {
						}
					}
					if (sock == null) {
						print("still can't connect");
						break;
					}
				}
			}
			try {
				String listcmd = "list\n";
				output.write(listcmd.getBytes());
			} catch (IOException e1) {
				print("output.write exception");
				close();
				continue;
			}
			byte[] b = new byte[65536];
			int count = 0;
			int loopcount = 0;
			while (true) {
				try {
					count = input.read(b, count, b.length - count);
				} catch (IOException e2) {
					print("input.read I/O exception");
				}
				if (count == -1) {
					print("input.read -1 (end of input)");
					// TODO: is a brief delay needed?
				}
				//print("got " + count + " bytes");
				if (count > 0 && b[count-1] == 0) {
					print("last bytes is null term, good :)");
					break;
				}
				if (++loopcount > 6) {
					// too many tries, give up :-(
					print("input give up");
					close();
					break;
				}
			}
			if (sock == null) continue;
			String data = new String(b, 0, count-1);
			String[] lines = data.split("\n");
			//print("found " + lines.length + " lines");
			BoardPort board;
			for (int i=0; i < lines.length; i++) {
				String[] fields = lines[i].split(" ", 2);
				//print("found " + fields.length + " fields");
				if (fields.length == 2) {
					board = new BoardPort();
					board.setProtocol("Teensy"); // Ports menu category
					board.setAddress(fields[0]); // used to open port
					String label = fields[1];
					if (label.startsWith("\\\\?\\")) {
						label = label.substring(4);
					}
					int teensyindex = label.indexOf("(Teensy");
					if (teensyindex > 23) {
						label = label.substring(0, 23) + "... " +
							label.substring(teensyindex);
					}
					board.setLabel(label); // name shown in Ports menu
					ports.add(board);
				}
			}
			return ports;
		}
		return ports;
	}

	void print(String str) {
		//System.err.println("TeensyDiscovery: " + str);
	}

	boolean connect() {
		String name = "teensy ports";
		int namelen = name.length();
		byte[] buf = new byte[namelen];
		byte[] namebuf = name.getBytes();
		InetAddress local;
		int[] addrlist = {28542,4985,18925};

		try {
			byte[] loop = new byte[] {127, 0, 0, 1};
			local = InetAddress.getByAddress("localhost", loop);
		} catch (Exception e1) {
			sock = null;
			return false;
		}
		for (int i=0; i<addrlist.length; i++) {
			try {
				sock = new Socket();
				InetSocketAddress addr = new InetSocketAddress(local, addrlist[i]);
				sock.connect(addr, 50);
				input = sock.getInputStream();
				output = sock.getOutputStream();
				sock.setSoTimeout(50);
			} catch (Exception e2) {
				sock = null;
				return false;
			}
			// check for welcome message
			try {
				int wait = 0;
				while (input.available() < namelen) {
					if (++wait > 5) throw new Exception();
					try {
						Thread.sleep(10);
					} catch (Exception e3) { }
				}
				input.read(buf, 0, namelen);
				for (int n=0; n<namelen; n++) {
					if (buf[n] !=  namebuf[n]) throw new Exception();
				}
			} catch (Exception e) {
				// mistakenly connected to some other program!
				close();
				continue;
			}
			print("connected to teensy_ports");
			return true;
		}
		sock = null;
		return false;
	}

	void close() {
		try {
			sock.close();
		} catch (Exception e4) { }
		sock = null;
	}

	synchronized boolean run_program() {
		String cmdline = BaseNoGui.getHardwarePath() + File.separator
			+ "tools" + File.separator + "teensy_ports";
		try {
			program = Runtime.getRuntime().exec(new String[] {cmdline});
		} catch (Exception e1) {
			program = null;
			return false;
		}
		return true;
	}

}
*/
