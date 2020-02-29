package com.westeroscraft.MigrateToBE;

import java.io.*;
import java.util.HashMap;
import java.util.List;

import com.westeroscraft.MigrateToBE.nbt.tag.CompoundTag;
import com.westeroscraft.MigrateToBE.nbt.tag.Tag;

import org.iq80.leveldb.*;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import static org.iq80.leveldb.impl.Iq80DBFactory.*;

public class MigrateToBE {
	private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();

    public static HashMap<String, String> idToBlockMap = new HashMap<String, String>();

    public static void initializeMapping() {
        //JSON parser object to parse read file
        JSONParser jsonParser = new JSONParser();
        try (FileReader reader = new FileReader("BlockID.json")) {
            //Read JSON file
            Object obj = jsonParser.parse(reader);
            JSONArray blockList = (JSONArray) obj;
            // Iterate over list
            blockList.forEach( blk -> handleBlock( (JSONObject) blk ) );
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (ParseException e) {
            e.printStackTrace();
        }
    }
    private static void handleBlock(JSONObject blk) {
        Long blkid = (Long) blk.get("id");
        Long meta = (Long) blk.get("data");
        String blkname = (String) blk.get("name");
        idToBlockMap.put(String.format("%d:%d", blkid, meta), blkname);
    }

	private static class MappedChunk {
        CompoundTag level;
        int bcnt;   // Number of blocks mapped
        int tescrubbed; // Number of tile entities scrubbed
        CompoundTag value;  // Base value for chunk
        byte[] biomes; // biome data (ZX order)
        List<CompoundTag> sections; // Chunk sections
        boolean empty;
        int cx, cz;

        @SuppressWarnings("unchecked")
        MappedChunk(Tag<?> lvl) throws IOException {
            level = (CompoundTag) lvl;
            value = level.getCompound("Level");
			if (value == null) {
				throw new IOException("Chunk is missing Level data");            
			}
            // Get sections of chunk
            sections = value.getList("Sections", CompoundTag.class);
            if (sections == null) {
				throw new IOException("No value for Sections in chunk");
			}
            empty = sections.size() == 0;
            bcnt = tescrubbed = 0;
            cx = value.getInt("xPos");
            cz = value.getInt("zPos");
		}
		
        // Process chunk
        void processChunk(DB db) throws IOException {
            empty = true;
            // Loop through the sections
            for (CompoundTag sect : sections) {
                empty = processSection(sect, db) & empty;
            }
        }
        CompoundTag findSection(int y) {
            for (CompoundTag sect : sections) {
                int yy = sect.getByte("Y");
                if (yy == y) {
                    return sect;
                }
            }
            return null;
        }		
        boolean processSection(CompoundTag sect, DB db) throws IOException {
            int y = sect.getByte("Y");
            byte[] blocks = sect.getByteArray("Blocks");
            if ((blocks == null) || (blocks.length < 4096)) {
				throw new IOException("Section missing Blocks field");
			}
            byte[] extblocks = sect.getByteArray("Add"); // Might be null
            if ((extblocks != null) && (extblocks.length < 2048)) {
                extblocks = new byte[2048];
			}
            byte[] data = sect.getByteArray("Data");
            if ((data == null) || (data.length < 2048)) {
				throw new IOException("Section missing Data field");
            }            
            // Load the bedrock subchunk
            byte[] key = BedrockSubChunk.makeSubChunkKey(cx, y, cz, 0);
            byte[] subchunkval = db.get(key);
            BedrockSubChunk bsc = new BedrockSubChunk(key, subchunkval, false);

            boolean isEmpty = true;
            for (int i = 0, j = 0; i < 4096; j++) { // YZX order
                int id, meta;
                int extid = 0;
                int datavals = data[j];
                if (extblocks != null) {
                    extid = 255 & extblocks[j];
                }
                // Process even values
                id = (255 & blocks[i]) | ((extid & 0xF) << 8);
                if (id != 0) {
                    meta = (datavals & 0xF);
                    if (mapValue(id, meta, bsc, i & 0xF, (i >> 8) & 0xF, (i >> 4) & 0xF)) {
                        bcnt++;
                    }
                    isEmpty = false;
                }
                i++;
                // Process odd values
                id = (255 & blocks[i]) | ((extid & 0xF0) << 4);
                if (id != 0) {
                    meta = (datavals & 0xF0) >> 4;
                    if (mapValue(id, meta, bsc, i & 0xF, (i >> 8) & 0xF, (i >> 4) & 0xF)) {
                        bcnt++;
                    }
                    isEmpty = false;
                }
                i++;
            }
            // Get new data for bedrock section
            byte[] newdata = bsc.getValue();
            db.put(key, newdata);

            return isEmpty;
        }
        // Map value, if needed
        public boolean mapValue(int id, int meta, BedrockSubChunk bsc, int bx, int by, int bz) {
            String lookup = idToBlockMap.get(String.format("%d:%d", id, meta));
            // If mapping found, change block to it
            if (lookup != null) {
                bsc.getStorageChunk(0).setBlockToBlockName(bx, by, bz, lookup);
                return true;
            }
            return false;
        }
    }
	
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

	private static void processWorldMapping(File src, DB db) throws IOException {
        File[] srcfiles = src.listFiles();
        if (srcfiles == null) return;
        
        for (File srcfile : srcfiles) {
            String srcname = srcfile.getName();
            if (srcfile.isDirectory()) {
            }
            else if (srcname.endsWith(".mca")) {    // If region file
                processRegionFile(srcfile, db);
            }
        }
	}
	
	 // Process a region file
	 private static void processRegionFile(File srcfile, DB db) throws IOException {
        int bcnt = 0;
        int cupdated = 0;
        RegionFile srcf = null;
        try {
            // Load region file
            srcf = new RegionFile(srcfile);
            srcf.load();
            int cnt = 0;
            for (int x = 0; x < 32; x++) {
                for (int z = 0; z < 32; z++) {
                    if(srcf.chunkExists(x, z)) {   // If chunk exists
                        cnt++;
                        Tag<?> tag = srcf.readChunk(x, z);
                        if (tag == null) {
							System.err.println("Chunk " + x + "," + z + " exists but not read");
							continue;
						}
                        MappedChunk mc = new MappedChunk(tag);
                        mc.processChunk(db);
                        bcnt += mc.bcnt;
                        if (mc.bcnt > 0) cupdated++;
                    }
                }
            }
            System.out.println("Region " + srcfile.getPath() + ", " + cnt + " chunks: updated " + bcnt + " blocks in " + cupdated + " chunks");
            		
        } finally {
            if (srcf != null) {
                srcf.cleanup();
            }
        }
	}
	
	public static void main(String[] args) {
		DB db = null;
		DBIterator iter = null;
		Snapshot ss = null;

        initializeMapping();

        System.out.println("MigrateToBE starting");
		String bedrock_dir = "CastleBlackBE";
        String javaedition_dir = "CastleBlack";
        if (args.length > 0)
            bedrock_dir = args[0];
        if (args.length > 1)
            javaedition_dir = args[1];
		// Set source director for java edition world
		JavaEditionChunk.setBasePath(javaedition_dir);

		try {
			Options options = new Options();
			// options.createIfMissing(true);
			options.compressionType(CompressionType.ZLIB).verifyChecksums(false).
				blockSize(256*1024).cacheSize(8*1024*1024).writeBufferSize(512*1024*1024);
			db = factory.open(new File(bedrock_dir + "/db"), options);

			processWorldMapping(new File(javaedition_dir, "region"), db);

			// iter = db.iterator();
			// for (iter.seekToFirst(); iter.hasNext(); iter.next()) {
			// 	Entry<byte[], byte[]> rec = iter.peekNext();
			// 	byte[] key = rec.getKey();
			// 	// Not a subchunk key length
			// 	if ((key.length != 10) && (key.length != 14)) {
			// 		continue;
			// 	}
			// 	// Not a subchunk record
			// 	if (key[key.length - 2] != (byte) 47) {
			// 		continue;
			// 	}
			// 	byte[] in = rec.getValue();
			// 	BedrockSubChunk bsc = new BedrockSubChunk(key, in, false);
			// 	if (!bsc.isGood()) {
			// 		System.out.println("bad subchunk");
			// 		continue;
			// 	}
			// 	JavaEditionChunk jec = new JavaEditionChunk(bsc.getCX(), bsc.getCZ(), bsc.getDIM());
			// 	jec.loadChunk();

			// 	BedrockSubChunk.StorageChunk sc = bsc.getStorageChunk(0);
			// 	int idx = sc.findInPalette("minecraft:ice");
			// 	if (idx >= 0) {
			// 		sc.replaceInPalette(idx, "wb:stone_block_0_12");
			// 	}
			// 	byte[] out = bsc.getValue();
			// 	boolean match = true;

			// 	// if (in.length != out.length) {
			// 	// 	System.out.println("inlen=" + in.length + ",outlen=" + out.length);
			// 	// 	match = false;
			// 	// } else {
			// 	// 	for (int i = 0; i < in.length; i++) {
			// 	// 		if (in[i] != out[i]) {
			// 	// 			match = false;
			// 	// 			break;
			// 	// 		}
			// 	// 	}
			// 	// }
			// 	// if (!match) {
			// 	// 	int minlen = Math.min(in.length, out.length);
			// 	// 	for (int i = 0; i < minlen; i++) {
			// 	// 		char c = (in[i] == out[i]) ? '=' : '!';
			// 	// 		System.out.print(String.format("%02X%c%02X ", in[i], c, out[i]));
			// 	// 		if ((i % 16) == 15)
			// 	// 			System.out.println();
			// 	// 	}
			// 	// 	bsc = new BedrockSubChunk(key, in, true);
			// 	// 	bsc.getValue(true);

			// 	// 	System.exit(1);
			// 	// }
			// 	db.put(key, out);
			// }
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
			db = factory.open(new File(bedrock_dir + "/db"), options);
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