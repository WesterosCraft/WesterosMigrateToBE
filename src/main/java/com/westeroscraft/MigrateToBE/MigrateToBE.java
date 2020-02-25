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
		DBIterator iter = null;
		Snapshot ss = null;
		System.out.println("MigrateToBE starting");
		try {
			Options options = new Options();
			// options.createIfMissing(true);
			options.compressionType(CompressionType.ZLIB).verifyChecksums(false).
				blockSize(256*1024).cacheSize(8*1024*1024).writeBufferSize(512*1024*1024);
			db = factory.open(new File("CastleBlack/db"), options);

			ReadOptions ro = new ReadOptions();
			ss = db.getSnapshot();
			ro.snapshot(ss);

			iter = db.iterator();
			for (iter.seekToFirst(); iter.hasNext(); iter.next()) {
				Entry<byte[], byte[]> rec = iter.peekNext();
				byte[] key = rec.getKey();
				// Not a subchunk key length
				if ((key.length != 10) && (key.length != 14)) {
					continue;
				}
				// Not a subchunk record
				if (key[key.length - 2] != (byte) 47) {
					continue;
				}
				byte[] in = rec.getValue();
				BedrockSubChunk bsc = new BedrockSubChunk(key, in, false);
				if (!bsc.isGood()) {
					System.out.println("bad subchunk");
					continue;
				}
				BedrockSubChunk.StorageChunk sc = bsc.getStorageChunk(0);
				int idx = sc.findInPalette("minecraft:ice");
				if (idx >= 0) {
					sc.replaceInPalette(idx, "wb:stone_block_0_12");
				}
				byte[] out = bsc.getValue();
				boolean match = true;

				// if (in.length != out.length) {
				// 	System.out.println("inlen=" + in.length + ",outlen=" + out.length);
				// 	match = false;
				// } else {
				// 	for (int i = 0; i < in.length; i++) {
				// 		if (in[i] != out[i]) {
				// 			match = false;
				// 			break;
				// 		}
				// 	}
				// }
				// if (!match) {
				// 	int minlen = Math.min(in.length, out.length);
				// 	for (int i = 0; i < minlen; i++) {
				// 		char c = (in[i] == out[i]) ? '=' : '!';
				// 		System.out.print(String.format("%02X%c%02X ", in[i], c, out[i]));
				// 		if ((i % 16) == 15)
				// 			System.out.println();
				// 	}
				// 	bsc = new BedrockSubChunk(key, in, true);
				// 	bsc.getValue(true);

				// 	System.exit(1);
				// }
				db.put(key, out);
			}
		} catch (IOException iox) {
			System.out.println("ERROR: " + iox.toString());
		} finally {
			if (iter != null) {
				try {
					System.out.println("Close iterator");
					iter.close();
				} catch (IOException x) {
					System.out.println("ERROR: " + x.getMessage());
				}
			}
			if (ss != null) {
				try {
					System.out.println("Close snapshot");
					ss.close();
				} catch (IOException x) {
					System.out.println("ERROR: " + x.getMessage());
				}
			}
			// Make sure you close the db to shutdown the
			// database and avoid resource leaks.
			if (db != null) {
				try {
					System.out.println("Closing DB");
					db.close();
					db = null;
					System.out.println("Close DB");
				} catch (IOException x) {
					System.out.println("ERROR: " + x.getMessage());
				}
			}
		}
		System.out.println("Compacting DB");
		try {
			
			Options options = new Options();
			// options.createIfMissing(true);
			options.compressionType(CompressionType.ZLIB).verifyChecksums(false).
				blockSize(256*1024).cacheSize(8*1024*1024).writeBufferSize(512*1024*1024);
			db = factory.open(new File("CastleBlack/db"), options);
		} catch (IOException iox) {
			System.out.println("ERROR: " + iox.toString());
		} finally {
			// database and avoid resource leaks.
			if (db != null) {
				try {
					System.out.println("Closing DB");
					db.close();
					db = null;
					System.out.println("Close DB");
				} catch (IOException x) {
					System.out.println("ERROR: " + x.getMessage());
				}
			}
		}
		System.out.println("MigrateToBE exiting");
	}
}