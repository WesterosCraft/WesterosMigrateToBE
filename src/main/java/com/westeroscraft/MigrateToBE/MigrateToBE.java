package com.westeroscraft.MigrateToBE;

import java.io.*;
import java.util.Map.Entry;

import org.iq80.leveldb.*;
import static org.iq80.leveldb.impl.Iq80DBFactory.*;

public class MigrateToBE {
	private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();

	public static String bytesToHex(byte[] bytes) {
		char[] hexChars = new char[bytes.length * 3];
		for (int j = 0; j < bytes.length; j++) {
			int v = bytes[j] & 0xFF;
			hexChars[j * 3] = HEX_ARRAY[v >>> 4];
			hexChars[j * 3 + 1] = HEX_ARRAY[v & 0x0F];
			hexChars[j * 3 + 2] = ':';
		}
		return new String(hexChars);
	}

	public static void main(String[] args) {
		DB db = null;
		System.out.println("MigrateToBE starting");
		try {
			Options options = new Options();
			//options.createIfMissing(true);
			options.compressionType(CompressionType.ZLIB_RAW);
			db = factory.open(new File("CastleBlack/db"), options);

			DBIterator iter = db.iterator();
			while (iter.hasNext()) {
				Entry<byte[], byte[]> rec = iter.next();
				byte[] key = rec.getKey();
				// Not a subchunk key length
				if ((key.length != 10) && (key.length != 14)) {
					continue;
				}
				// Not a subchunk record
				if (key[key.length-2] != (byte)47) {
					continue;
				}
				byte[] in = rec.getValue();
				BedrockSubChunk bsc = new BedrockSubChunk(key, in, false);
				if (!bsc.isGood()) {
					System.out.println("bad subchunk");
					continue;
				}

				byte[] out = bsc.getValue();

				 //if (in.length != out.length) {
				 //	System.out.println("inlen=" + in.length + ",outlen=" + out.length);
				 //	bsc = new BedrockSubChunk(key, in, true);
				 //	bsc.getValue(true);
				 //}
				// else {
				// 	boolean match = true;
				// 	for (int i = 0; i < in.length; i++) {
				// 		if (in[i] != out[i]) { match = false; break; }
				// 	}
				// 	if (!match) {
				// 		for (int i = 0; i < in.length; i++) {
				// 			char c = (in[i] == out[i])?'=':'!';
				// 			System.out.print(String.format("%02X%c%02X ", in[i], c, out[i]));
				// 			if ((i % 16) == 15) System.out.println();
				// 		}
				// 		bsc.getValue(true);
				// 		System.exit(1);
				// 	}
				// }
				db.put(key, out);
			}
		} catch (IOException iox) {
			System.out.println("ERROR: " + iox.toString());
		} finally {
		  // Make sure you close the db to shutdown the 
		  // database and avoid resource leaks.
		  if (db != null) {
			  try {
				System.out.println("Closing DB");
				db.close();
				  System.out.println("Close DB");
			  } catch (IOException x) {
			  }
		  }
		}				
		System.out.println("MigrateToBE exiting");

	}
}