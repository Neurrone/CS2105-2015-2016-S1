import java.net.*;
import java.text.DateFormat;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WebProxy implements Runnable {
	// Socket for client connections
	private static ServerSocket socket = null;
	private Socket client = null;
	
	// these are the positions of various fields in the tokenized request header from client
	private static int HOST_INDEX = 0;
	private static int PORT_INDEX = 1;
	private static int PATH_INDEX = 2;
private static ArrayList<String> sensoredWords;
	private static Pattern contentLengthPattern = Pattern.compile("content-length\\:\\s+([0-9]+).*", Pattern.CASE_INSENSITIVE);
	private static Pattern contentTypePattern = Pattern.compile("Content-Type\\:\\s+text\\/html.*", Pattern.CASE_INSENSITIVE);   
private static Pattern lastModifiedPattern = Pattern.compile("Last-Modified\\:\\s+([ 0-9a-z,\\:\\-]+).*", Pattern.CASE_INSENSITIVE);
	private static ConcurrentHashMap<String, File> cache = new ConcurrentHashMap<String, File>();
	// maps urls in cache to their last-modified fields
	private static ConcurrentHashMap<String, String> lastModified = new ConcurrentHashMap<String, String>();
	// instead of using the url for file name and potentially risk creating files exceeding filename limits
	// a number is being used for the file name
	private static int filename = 0; // first file has name 0, second 1 etc

	WebProxy(Socket client) {
		this.client = client;
	}
	
	// creates the server socket at the specified port number
	private static void initServer(int port) throws IOException {
		socket = new ServerSocket(port);
	}
	
	// reads request from client socket. Client must be initialized first with socket.accept();
	private String readClientRequest() throws IOException {
		return readFromSocket(client);
	}
	
	// reads 1 http packet from the specified socket
	// can be used to read incoming requests from client or response from server
	private static String readFromSocket(Socket s) throws IOException {
		BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream()));
// 		InputStreamReader reader = new InputStreamReader(client.getInputStream());
StringBuilder request = new StringBuilder();
//for get requests, the request ends with a blank line
String line = reader.readLine();
int contentLength = -1; // uninitialized e.g for queries with no msg body   

// read the initial + header lines
while (line != null) {
		request.append(line.toCharArray());
	request.append('\r');
	request.append('\n');
	
	if (line.equals("")) {
		break;
	}
	
	// check if this line has content-length info
	Matcher m = contentLengthPattern.matcher(line);
	if (m.find()) {
		contentLength = new Integer(m.group(1));
	}

	line = reader.readLine();
}

if (contentLength != -1) {
	char[] buff = new char[8*1024];
	int bytesJustRead, totalBytesRead = 0;
	
	for (totalBytesRead = 0; totalBytesRead < contentLength; totalBytesRead += bytesJustRead) {
		bytesJustRead = reader.read(buff, 0, buff.length);
		request.append(buff, 0, bytesJustRead);	
	}
}

	return request.toString();
			}

	/* 
	// modifies the initial request line from client so that get url after get instead points to a local path
	// i.e remove http://hostname
	private static String replaceRequestUrlWithLocalPath(String request, String host) {
		return request.replaceFirst("http://" + host, "");
	}
	*/
	
	// extract the first line from a http message
	private static String getFirstLine(String message) {
		return message.substring(0, message.indexOf('\r'));
	}
	
	// process an incoming client request
	private void processClientRequest(String request) throws IOException, UnknownHostException {
	String firstLine = getFirstLine(request);
	
	String[] params = firstLine.split(" ");
	// String method = params[0];
	String url = params[1];
	// String version = params[2];
	String[] fields = tokenizeUrl(url);
	String host = fields[HOST_INDEX];
	Integer port = new Integer(fields[PORT_INDEX]);
	// String fixedRequest = replaceRequestUrlWithLocalPath(request, host);
							// Check cache if file exists
	
	if (isUrlInCache(url) && isCachedUrlStillValid(request, url, host, port))
		returnCachedResponseToClient(url);
	else {// relay client's request, streaming response back to client, and caching it as well
				proxyRequest(request, url, host, port);
	}
		
				client.close();
	}
	
	// writes a http packet into a socket
	private static void writeToSocket(String message, Socket s) throws IOException {
		BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(s.getOutputStream()));

	writer.write(message, 0, message.length());
	writer.flush();
	}
	
	// forwards the client's request, and streams responses received to server back to client
	private void proxyRequest(String clientRequest, String url, String remoteHost, int remotePort) throws UnknownHostException, IOException, IllegalArgumentException {
		BufferedWriter cacheWriter= addUrlToCache(url);
		Socket remote;
	
		try {
		remote = new Socket(remoteHost, remotePort);
		}
		catch(Exception e) {
			String response ="HTTP/1.0 502 Bad Gateway\r\nConnection: close\r\n"; 
			writeToSocket(response, client);
			cacheWriter.write(response, 0, response.length());
			cacheWriter.close();
			return;
		}
		
		writeToSocket(clientRequest, remote);

		BufferedReader reader = new BufferedReader(new InputStreamReader(remote.getInputStream()));
		BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(client.getOutputStream()));
		
		
//for responses without additional content, they end with a blank line
String line = reader.readLine();
int contentLength = -1; // uninitialized e.g for queries with no msg body   
boolean isSensorable = false;

// read the initial + header lines
while (line != null) {
	System.out.println(line);
	writer.write(line, 0, line.length());
	writer.write('\r');
	writer.write('\n');
	cacheWriter.write(line, 0, line.length());
	cacheWriter.write('\r');
	cacheWriter.write('\n');

	if (line.equals("")) {
		break;
	}
	
	// check if this line has content-length info
	Matcher m = contentLengthPattern.matcher(line);
	if (m.find()) {
		contentLength = new Integer(m.group(1));
	}
	
	// check if this line has content-type: text/html
	if (isSensorable == false && isTextOrHtml(line)) {
		isSensorable = true;
	}
	
	// check if the line has last-modified info
	Matcher n = lastModifiedPattern.matcher(line);
	if (n.find()) {
		lastModified.put(url, new String(n.group(1)));
	}

	
	line = reader.readLine();
}

if (contentLength != -1) {
	char[] buff = new char[8*1024];
	int bytesJustRead, totalBytesRead = 0;
	
	for (totalBytesRead = 0; totalBytesRead < contentLength; totalBytesRead += bytesJustRead) {
		bytesJustRead = reader.read(buff, 0, buff.length);
		if (isSensorable) {
			char[] sensored = sensor(buff, bytesJustRead);
			writer.write(sensored, 0, sensored.length);
			cacheWriter.write(sensored, 0, sensored.length);
		}
		else {
		writer.write(buff, 0, bytesJustRead);
		cacheWriter.write(buff, 0, bytesJustRead);
		}
	}
	
}
else // for servers that have entity bodies but no content-length
{
	char[] buff = new char[8*1024];
	int bytesRead = reader.read(buff, 0, buff.length);
	
	while (bytesRead != -1) {
		if (isSensorable) {
			char[] sensored = sensor(buff, bytesRead);
			writer.write(sensored, 0, sensored.length);
			cacheWriter.write(sensored, 0, sensored.length);
		}
		else {
		writer.write(buff, 0, bytesRead);	
		cacheWriter.write(buff, 0, bytesRead);
		}
		bytesRead = reader.read(buff, 0, buff.length);
	}
}
		
	writer.flush();
	cacheWriter.flush();
	cacheWriter.close();
	remote.close();
	}

	// returns a boolean indicating whether this header line contents content-type: text/html
	private static boolean isTextOrHtml(String headerLine) {
		Matcher m = contentTypePattern.matcher(headerLine);
		return m.find();
	}
	
	// performs sensoring on the chars in the buffer, returning the sensored buffer
	private static char[] sensor(char[] buf, int size) {
		
		String str = new String(buf, 0, size);
		for (int i=0; i<sensoredWords.size(); i++) {
		str = str.replaceAll("(?i)" + Pattern.quote(sensoredWords.get(i)), "---");
	}
		
		return str.toCharArray();
	}
	
	private static String[] tokenizeUrl(String url) {
		String[] params = new String[3];
		
		// remove the http:// prefix
		String urlWithoutHttp = url.substring(7);
		// at this point, urlWithoutHttp has this form: <host>/<path to be forwarded to remote server>
		int slashIndex = urlWithoutHttp.indexOf('/');
		// if there is no trailing slash, append it to back e.g compnus.edu.sg
		if (slashIndex == -1) {
			urlWithoutHttp = urlWithoutHttp + '/';
			slashIndex = urlWithoutHttp.length() - 1;
		}

		int colonIndex = urlWithoutHttp.indexOf(':');
		if (colonIndex != -1 && colonIndex < slashIndex) {
			params[PORT_INDEX] = urlWithoutHttp.substring(colonIndex+1, slashIndex);
			params[HOST_INDEX] = urlWithoutHttp.substring(0, colonIndex);
		}
		else { // no port number given
			params[HOST_INDEX] = urlWithoutHttp.substring(0, slashIndex);
			params[PORT_INDEX] = "80";
		}
		
		params[PATH_INDEX] = urlWithoutHttp.substring(slashIndex);
		
		return params;
	}

	// retrieves the list of words to be sensored from sensor.txt,
	// storing them in sensoredWords
	private static void getSensoredWords() throws IOException {
		sensoredWords = new ArrayList<String>();
		
		File src = new File("sensor.txt");
		if (src.isFile() == false) {
			return;
		}

		BufferedReader reader = new BufferedReader(new FileReader(src));
String line = reader.readLine();

while (line != null) {
	sensoredWords.add(line.toLowerCase());
	line = reader.readLine();
}

reader.close();
	}
	
	private static boolean isUrlInCache(String url) {
		return cache.containsKey(url); 
	}

	// returns a cached response to the client
	private void returnCachedResponseToClient(String url) throws IOException {
		System.out.println("sending cached response to " + url + " to client ");
		File f = cache.get(url);
		// BufferedReader reader = new BufferedReader(new FileReader(f));
BufferedInputStream reader = new BufferedInputStream(new FileInputStream(f));
		// BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(client.getOutputStream()));
BufferedOutputStream writer = new BufferedOutputStream(client.getOutputStream());
		byte[] buff = new byte[8*1024];
		int bytesRead = reader.read(buff, 0, buff.length);
		
		while (bytesRead != -1) {
			writer.write(buff, 0, bytesRead);	
			bytesRead = reader.read(buff, 0, buff.length);
		}

		reader.close();
		writer.flush();
	}

	// add a new entry to the cache for the current url
	// this creates a new mapping between url and file, but nothing is actually written
	// a BufferedWriter is returned so that the response for this url can be written 
	private static BufferedWriter addUrlToCache(String url) throws IOException {
		File f = cache.get(url);
		
if (f == null) {
		f = new File("" + filename);
		filename++;
		cache.put(url, f);
}
		
		// BufferedOutputStream w = new BufferedOutputStream(new FileOutputStream(f));
if (f.isFile())
		return new BufferedWriter(new FileWriter(f, false));
else
		return new BufferedWriter(new FileWriter(f));
	}
	
	private static boolean isCachedUrlStillValid(String clientRequest, String url, String host, Integer port) throws IOException {
		if (lastModified.get(url) == null) {
			System.out.println(url + " did  not supply a last-modified field, retrieving again");
			return false; // this url is in the cache, but the website did not supply a last-modified field
		}
		
		String requestWithIfModifiedSince = clientRequest.replaceFirst("\\r\\n\\r\\n", "\r\nIf-Modified-Since: " + lastModified.get(url) + "\r\n\r\n");


		Socket remote;
		
		try {
		remote = new Socket(host, port);
		}
		catch(Exception e) {
return true; // used cached 502
		}
		
	System.out.println(requestWithIfModifiedSince);
		writeToSocket(requestWithIfModifiedSince, remote);
		
		BufferedReader reader = new BufferedReader(new InputStreamReader(remote.getInputStream()));		
		String firstLine = reader.readLine();

		remote.shutdownInput();
		reader.close();
		remote.close();
		
		return firstLine.indexOf("304") != -1;
	}
/* 
	private static String getCurrentTime() {
		  DateFormat formatter = new SimpleDateFormat("EEEE, dddd mmmm yyyy HH:mm:ss z");
		  GregorianCalendar cal = new GregorianCalendar(new SimpleTimezone(0, GMT), new Locale("en", "SG"));
		  String timestamp = formatter.format(cal.getTime());
		  return timestamp;
	}
	*/
	
	public static void main(String args[]) throws Exception {
		getSensoredWords();
initServer(new Integer(args[0]));

		while (true) {
			try {
			Socket c = socket.accept();
			System.out.println("'Received a connection from: " + c);
	        (new Thread(new WebProxy(c))).start();
							} 
			catch (Exception e) {
				System.out.println("Error accepting new connection from " + e);
				continue;
			}
		}			
		
	}

@Override
public void run() {
	
	try {
		String request = readClientRequest();
		System.out.println(request);
		processClientRequest(request);
					} 
	catch (Exception e) {
		System.out.println("Error reading request from client: " + e);
	}

}


}