/*
 * all packets originating from this sender has the following format:
*
* first 4 bytes: sequence number
* next 2 bytes: length, amount of data in bytes
* next length bytes: data. Will be interpreted as characters for the first packet from sender to receiver, which contains the file name. Otherwise, binary data.
* last 8 bytes: crc32
* max packet size: 1000 bytes, including all additional fields
*
* The receiver's acknowledgement has the following format:
*4 bytes for the sequence number of the packet being acknowledged
* 8 bytes of crc32 of those 4 bytes
* 
*If the receiver's ack for the last packet containing file data gets corrupted, the receiver will go into an infinite loop. It generates an ack for any non-corrupt packet received. This is so that the sender terminates when transfer completes. 
 */

import java.util.*;
import java.util.zip.CRC32;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.*;

public class FileSender {
private static final int HOST_NAME_POS = 0;
private static final int PORT_POS = 1;
private static final int SRC_FILE_POS = 2;
private static final int DEST_FILE_POS = 3;
private static final int bytesForFields = 4 + 2 + 8; // sequence, length and crc32	
private static final int INVALID_SEQUENCE_NUMBER = -1;
private static final int TIMEOUT = 1;
private static DatagramSocket socket;
private static InetSocketAddress address;
private static BufferedInputStream srcStream;
private static long fileSize;
private static int sequenceNum = 0;
public static void main(String[] args) throws IOException {
String host = args[HOST_NAME_POS];
int port = new Integer(args[PORT_POS]);
String destFilename = args[DEST_FILE_POS];
address = new InetSocketAddress(host, port);
socket = new DatagramSocket();
File srcFile = new File(args[SRC_FILE_POS]);
srcStream = new BufferedInputStream(new FileInputStream(srcFile));
fileSize = srcFile.length();

sendFileInfo(destFilename);
sendFile();
srcStream.close();
	}

private static long getHash(ByteBuffer data, int offset, int length) {
	CRC32 hash = new CRC32();
	hash.update(data.array(), offset, length);
	return hash.getValue();
}

private static ByteBuffer getByteBuffer(int size) {
	byte[] data = new byte[size];
	ByteBuffer buf = ByteBuffer.wrap(data);
	buf.clear();
	buf.rewind();
	return buf;
}

private static void sendFileInfo(String destFilename) throws IOException {
	int filenameLengthInBytes = destFilename.length()*2; // a char is 2 bytes
	int packetLength = bytesForFields + filenameLengthInBytes+8; // additional 8 bytes for the file size
	ByteBuffer buf = getByteBuffer(packetLength);
	buf.putInt(0); // sequence number
	buf.putShort((short) (filenameLengthInBytes+8));
	buf.putLong(fileSize);
	for (int i=0; i<destFilename.length(); i++)
		buf.putChar(destFilename.charAt(i));

	addHashToPacket(buf, packetLength);
DatagramPacket packet = new DatagramPacket(buf.array(), packetLength, address);


int ackNum = INVALID_SEQUENCE_NUMBER;
ByteBuffer replyBuf = getByteBuffer (1000);
DatagramPacket reply = new DatagramPacket(replyBuf.array(), 1000);
socket.setSoTimeout(TIMEOUT);
// packet.setLength(1000);
// send the file name. If receiver doesn't reply within 10 ms or its ack is corrupt, resend
while (ackNum != sequenceNum) {
	socket.send(packet);
	try {
	socket.receive(reply);
	ackNum = getSequenceNumberOfAck(replyBuf, reply.getLength()); // set to INVALID_SEQUENCE_NUMBER if corrupt
	}
	catch (SocketTimeoutException e) {
		// System.out.println("timeout");
	}
}
incrementSequenceNum();	
}

// adds the crc32 of the rest of the packet to the last 8 bytes of the packet
// this method does not change the current position of the ByteBuffer
private static void addHashToPacket(ByteBuffer packetWithoutHash, int packetLength) {
	long hash = getHash(packetWithoutHash, 0, packetLength-8); // because last 8 bytes are for the crc32
	packetWithoutHash.putLong(packetLength-8, hash);	
}

private static int getSequenceNumberOfAck(ByteBuffer ack, int packetLength) {
	if (packetLength != 12)
		return INVALID_SEQUENCE_NUMBER;

	long actualHash = getHash(ack, 0, packetLength-8); // because last 8 bytes are for the crc32
	long receivedHash = ack.getLong(4);
	
	if (actualHash == receivedHash)
	return ack.getInt(0); // sequence number being acked
	
	return INVALID_SEQUENCE_NUMBER;
}

private static void sendFile() throws IOException {
	long bytesLeftToSend = fileSize;
	
	while (bytesLeftToSend > 0) {
short payloadLength = (short)((bytesLeftToSend < 1000 - bytesForFields) ? bytesLeftToSend : 1000 - bytesForFields);
int packetLength = payloadLength+ bytesForFields;
ByteBuffer buf = getByteBuffer(packetLength);
buf.putInt(sequenceNum); // sequence number
buf.putShort(payloadLength);
srcStream.read(buf.array(), 6, payloadLength);
addHashToPacket(buf, packetLength);

DatagramPacket packet = new DatagramPacket(buf.array(), packetLength, address);

int ackNum = INVALID_SEQUENCE_NUMBER;
ByteBuffer replyBuf = getByteBuffer (1000);
DatagramPacket reply = new DatagramPacket(replyBuf.array(), 1000);
//send the payload. If receiver doesn't reply within 10 ms or its ack is corrupt, resend
while (ackNum != sequenceNum) {
	socket.send(packet);
	// System.out.println("Sent packet " + sequenceNum);
	try {
	socket.receive(reply);
	ackNum = getSequenceNumberOfAck(replyBuf, reply.getLength()); // set to INVALID_SEQUENCE_NUMBER if corrupt
	// if (ackNum != sequenceNum)
		// System.out.println("the acknowledgement for packet " + sequenceNum + " is corrupt");
	}
	catch (SocketTimeoutException e) {
		// System.out.println("timeout while waiting for ack for packet " + sequenceNum);
	}
}
// System.out.println("receiver has successfully received " + sequenceNum);
incrementSequenceNum();	
bytesLeftToSend -= payloadLength;
	}
}

private static void incrementSequenceNum() {
	sequenceNum++;
	if (sequenceNum < 0)
		sequenceNum = 0;
}

}
