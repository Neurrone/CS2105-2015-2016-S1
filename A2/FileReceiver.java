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
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.*;

public class FileReceiver {
	private static final int bytesForFields = 4 + 2 + 8; // sequence, length and crc32
private static DatagramSocket socket;
private static SocketAddress senderAddress;
private static BufferedOutputStream outStream;
private static int nextSequenceNumberExpected = 0;
private static long fileSize;
private static String filename;

	public static void main(String[] args) throws IOException {
		int port = Integer.parseInt(args[0]);
socket = new DatagramSocket(port);		
receiveFileInfo();
System.out.println("filename = " + filename+ " size = " + fileSize);
File destFile = new File(filename);
outStream = new BufferedOutputStream(new FileOutputStream(destFile));

receiveFile();
outStream.close();
informSenderOfLastPacketReceipt(); // go into an infinite loop and resend acks if sender resends last packet
	}

	private static ByteBuffer getByteBuffer(int size) {
		byte[] data = new byte[size];
		ByteBuffer buf = ByteBuffer.wrap(data);
		buf.clear();
		buf.rewind();
		return buf;
	}

	private static void receiveFileInfo() throws IOException{
		ByteBuffer buf = getByteBuffer(1000);
		byte[] arr = buf.array();
		DatagramPacket packet = new DatagramPacket(buf.array(), 1000);
		packet.setLength(1000);
		boolean waitingForSender = true;
		
		while (waitingForSender) {
			socket.receive(packet);	
			if (isPacketCorrupt(buf, packet.getLength()) == false) {
				senderAddress = packet.getSocketAddress();
				buf.rewind();
				int sequenceNum = buf.getInt();
				sendAck(sequenceNum);
				
				if (sequenceNum == nextSequenceNumberExpected)
				waitingForSender = false;
					}
		}
		
		incrementSequenceNum();
		buf.rewind();
		senderAddress = packet.getSocketAddress();
		
		buf.getInt(); // sequence number
		short length = buf.getShort();
		fileSize = buf.getLong();
		int filenameLength = (length-8)/2; // subtract 8 for the file size, and because each char is 2 bytes
		StringBuilder str = new StringBuilder(filenameLength);
		for (int i=0; i<filenameLength; i++)
			str.append(buf.getChar());
		
		filename = str.toString();
}
	
	private static long getHash(ByteBuffer data, int offset, int length) {
		CRC32 hash = new CRC32();
		hash.update(data.array(), offset, length);
		return hash.getValue();
	}
	
	private static boolean isPacketCorrupt(ByteBuffer data, int receivedLength) { 
	if (receivedLength < 8) 
		return true;
	
	short payloadLength = data.getShort(4);
	if (receivedLength != bytesForFields+payloadLength)
		return true;
	// the range of indices that contain the crc32 should be in [receivedLength-8, receivedLength-1]
	long receivedHash = data.getLong(receivedLength-8);
	long actualHash = getHash(data, 0, receivedLength-8);
	return receivedHash != actualHash;
	}

	private static void sendAck(int sequenceNum) throws IOException {
		ByteBuffer ack = getByteBuffer(12); // 4 for sequence num and 8 for crc
		ack.putInt(sequenceNum);
addHashToPacket(ack, 12);		
DatagramPacket packet = new DatagramPacket(ack.array(), 12, senderAddress);
socket.send(packet);
	}
	
	// adds the crc32 of the rest of the packet to the last 8 bytes of the packet
	private static void addHashToPacket(ByteBuffer packetWithoutHash, int packetLength) {
		long hash = getHash(packetWithoutHash, 0, packetLength-8); // because last 8 bytes are for the crc32
		packetWithoutHash.putLong(hash);	
	}

	private static void receiveFile() throws IOException {
long totalBytesReceived = 0;
ByteBuffer buf = getByteBuffer(1000);
DatagramPacket packet = new DatagramPacket(buf.array(), 1000);
packet.setLength(1000);

while (totalBytesReceived < fileSize) {
	
	socket.receive(packet);	

// 	if (isPacketCorrupt(buf, packet.getLength()))
// 	System.out.println("corrupted packet of length " + packet.getLength() + " received while expecting " + nextSequenceNumberExpected);

	if (isPacketCorrupt(buf, packet.getLength()) == false) {
		buf.rewind();
		int sequenceNum = buf.getInt();
		// System.out.println("rcv sequence num " + sequenceNum + " expecting " + nextSequenceNumberExpected);
		sendAck(sequenceNum);
		
		if (sequenceNum == nextSequenceNumberExpected) {
			// write the payload to disk
			short length = buf.getShort();
			totalBytesReceived+=length;
			// the file's contents should be from index [6, 6+length-1]
			outStream.write(buf.array(), 6, length);
			incrementSequenceNum();
		}
	}
	}
	
outStream.flush();
	}

	private static void informSenderOfLastPacketReceipt() throws IOException {
		ByteBuffer buf = getByteBuffer(1000);
		DatagramPacket packet = new DatagramPacket(buf.array(), 1000);
		packet.setLength(1000);
		
while (true) {
	socket.receive(packet);

		if (isPacketCorrupt(buf, packet.getLength()) == false) {
			buf.rewind();
			int sequenceNum = buf.getInt();
			sendAck(sequenceNum);
		}
}

	}

	private static void incrementSequenceNum() {
		nextSequenceNumberExpected++;
		if (nextSequenceNumberExpected < 0) // overflow
			nextSequenceNumberExpected= 0;
	}

}