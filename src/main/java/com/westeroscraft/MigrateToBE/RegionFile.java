package com.westeroscraft.MigrateToBE;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.BitSet;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import com.westeroscraft.MigrateToBE.nbt.tag.Tag;
import com.westeroscraft.MigrateToBE.nbt.stream.NBTInputStream;

public class RegionFile {
    private File rfile;
    private BitSet alloc_table = new BitSet();
    private int[] chunkoff = new int[1024];
    private int[] chunklen = new int[1024];
    private int[] timestamp = new int[1024];
    private RandomAccessFile raf;
    
    public RegionFile(File f) throws IOException {
        rfile = f;
        if (rfile.exists()) {
            load();
        }
    }
    public void cleanup() {
        alloc_table.clear();    // Reset tables
        alloc_table.set(0); // First two are always allocated
        alloc_table.set(1);
        chunkoff = new int[1024];
        chunklen = new int[1024];
        timestamp = new int[1024];
        if (raf != null) { try { raf.close(); } catch (IOException x) {};  raf = null; }
    }
    
    public void load() throws IOException {
        cleanup();
        
        // Now create access file to read chunk
        raf = new RandomAccessFile(rfile, "rw");
        long initlen = raf.length();
        if (initlen < 8192) {   // Proper file needs to be at least 8192 bytes
            throw new IOException("Missing initial chunk tables: length=" + initlen);
        }
        byte[] buf = new byte[4096];
        // First 4K is chunk offset/length data
        raf.read(buf);  // read bytes
        for (int i = 0, boff = 0; i < 1024; i++) {
            for (int b = 0; b < 3; b++) {
                chunkoff[i] = (chunkoff[i] << 8) | (255 & buf[boff++]);
            }
            chunklen[i] = (255 & buf[boff++]);
            // If zero, no sectors
            if (chunkoff[i] == 0) continue;
            // Now, mark sectors as allocated
            for (int sect = chunkoff[i]; sect < (chunkoff[i] + chunklen[i]); sect++) {
                if (alloc_table.get(sect)) {    // Already allocated?
                    throw new IOException("Bad chunk map: chunk " + i + " extends to already allocated sector " + sect + " in " + rfile.getPath());
                }
                alloc_table.set(sect);
            }
        }
        // Next 4K is timestamps
        raf.read(buf);  // read bytes
        for (int i = 0, boff = 0; i < 1024; i++) {
            for (int b = 0; b < 4; b++) {
                timestamp[i] = (timestamp[i] << 8) | (255 & buf[boff++]);
            }
        }
    }
    
    // Map X,Z chunk coord to index
    private final int getIndex(int x, int z) {
        return x + (z * 32);
    }
        
    // Check if chunk exists
    public boolean chunkExists(int x, int z) {
        if ((x < 0) || (x > 31) || (z < 0) || (z > 31)) {
            return false;
        }
        int idx = getIndex(x, z);
        return (chunkoff[idx] > 0) && (chunklen[idx] > 0);
    }
    
    // Read chunk, return as data stream
    public Tag<?> readChunk(int x, int z) throws IOException {
        // Sanity check chunk coordinates
        if ((x < 0) || (x > 31) || (z < 0) || (z > 31)) {
            return null;
        }
        int idx = getIndex(x, z);   // Get index for chunk
        if (chunkoff[idx] <= 0) {   // Unallocated chunk?
            return null;
        }
        long baseoff = 4096L * chunkoff[idx];   // Get offset
        int cnt = chunklen[idx]; // Get chunk count
        raf.seek(baseoff);  // Seek to chunk
        int clen = raf.readInt();   // Read chunk byte count
        if ((clen > (cnt * 4096)) || (clen <= 0)) {  // Not enough data?
            throw new IOException("Length longer than space: " + clen + " > " + (cnt * 4096));
        }
        int encoding = raf.readByte(); // Get encoding for chunk
        byte[] buf = null;
        InputStream in = null;
        switch (encoding) {
            case 1:
                buf = new byte[clen - 1];   // Get buffer for bytes (length has 1 extra)
                raf.read(buf);  // Read whole compressed chunk
                // And return stream to decompress it
                in = new GZIPInputStream(new ByteArrayInputStream(buf));
                break;
            case 2:
                buf = new byte[clen - 1];   // Get buffer for bytes (length has 1 extra)
                raf.read(buf);  // Read whole compressed chunk
                in = new InflaterInputStream(new ByteArrayInputStream(buf));
                break;
            default:
                throw new IOException("Bad encoding=" + encoding);
        }
        DataInputStream dis = new DataInputStream(in);
        NBTInputStream nis = new NBTInputStream(dis);
        try {
            return nis.readTag();
        } finally {
            nis.close();
        }
    }
}