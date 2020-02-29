package com.westeroscraft.MigrateToBE;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

import com.westeroscraft.MigrateToBE.nbt.CompoundTagBuilder;
import com.westeroscraft.MigrateToBE.nbt.NbtUtils;
import com.westeroscraft.MigrateToBE.nbt.stream.NBTInputStream;
import com.westeroscraft.MigrateToBE.nbt.stream.NBTOutputStream;
import com.westeroscraft.MigrateToBE.nbt.tag.CompoundTag;

public class BedrockSubChunk {
    private final int cx, cy, cz;
    private final int dim;
    private int ver;
    private final byte[] key;
    private boolean good = false;
    private static final int keySizes[] = { 1, 2, 3, 4, 5, 6, 8, 16 };
    private static void writeLEInt(OutputStream out, int val) throws IOException {
        byte[] v = new byte[4];
        for (int i = 0; i < 4; i++) {
            v[i] = (byte)((val >> (i*8)) & 0xFF);
        }
        out.write(v);
    }
    private static int readLEInt(InputStream in) throws IOException {
        byte[] v = new byte[4];
        in.read(v);
        int val = 0;
        for (int i = 0; i < 4; i++) {
            val |= (((int)v[i]) & 0xFF) << (8*i);
        }
        return val;
    }

    public static byte[] makeSubChunkKey(int cx, int cy, int cz, int dim) {
        byte[] key;
        if (dim == 0) {
            key = new byte[10];
        }
        else {
            key = new byte[14];
        }
        int off = 0;
        // Write chunk X
        for (int i = 0; i < 4; i++) {
            key[off++] = (byte)((cx >> (i*8)) & 0xFF);
        }
        // Write chunk Z
        for (int i = 0; i < 4; i++) {
            key[off++] = (byte)((cz >> (i*8)) & 0xFF);
        }
        if (dim != 0) {
            // Write DIM
            for (int i = 0; i < 4; i++) {
                key[off++] = (byte)((dim >> (i*8)) & 0xFF);
            }
        }
        key[off++] = (byte) 47; // tag ID
        key[off++] = (byte) cy;
        return key; 
    }

    public class StorageChunk {
        private ArrayList<CompoundTag> blockPalette = new ArrayList<CompoundTag>();
        // Ordered (X*256) + (Z*16) + Y
        private int[] blocks = new int[4096];

        private void loadFromInputStream(InputStream bais, int storeid, boolean debug) throws IOException {
            int storageVersion = bais.read();
            int bitsPerBlock = storageVersion >> 1;
            int blocksPerWord = 32 / bitsPerBlock;
            int wordCount = (4095 + blocksPerWord) / blocksPerWord;
            int mask = ((1 << bitsPerBlock) - 1);
            for (int i = 0, idx = 0; i < wordCount; i++) {
                int word = readLEInt(bais);
                for (int j = 0; j < blocksPerWord; j++) {
                    blocks[idx++] = (word >> (j * bitsPerBlock)) & mask;
                    if (idx > 4095) break;
                }
            }
            int paletteSize = readLEInt(bais);
            NBTInputStream stream = NbtUtils.createReaderLE(bais);
            for (int i = 0; i < paletteSize; i++) {
                CompoundTag ptag = (CompoundTag) stream.readTag();
                //System.out.println("store=" + storeid + ",id=" + i + ":" + ptag.toString());
                blockPalette.add(ptag);
            }
            stream.close();
            if (debug)
                System.out.println(String.format("in%d: sv=%d,bpb=%d,bpw=%d,wc=%d,m=%x,ps=%d", storeid, storageVersion, bitsPerBlock, blocksPerWord, wordCount, mask, paletteSize));
        }
        private void writeToOutputStream(OutputStream baos, int storeid, boolean debug) throws IOException {
            int maxval = 0;
            for (int i = 0; i < blocks.length; i++) {
                if (maxval < blocks[i]) maxval = blocks[i];
            }
            int paletteSize = blockPalette.size();   // Get count
            if (paletteSize == 0) {
                addToPalette("minecraft:air");
                paletteSize = blockPalette.size();
            }
            int bitsPerBlock = 16;
            for (int i = 0; i < keySizes.length; i++) {
                if (maxval < (1 << keySizes[i])) {
                    bitsPerBlock = keySizes[i];
                    break;
                }
            }
            byte storageVersion = (byte)(bitsPerBlock << 1);
            baos.write(bitsPerBlock << 1);
            int blocksPerWord = 32 / bitsPerBlock;
            int wordCount = (4095 + blocksPerWord) / blocksPerWord;
            int mask = ((1 << bitsPerBlock) - 1);
            for (int i = 0, idx = 0; i < wordCount; i++) {
                int word = 0;
                for (int j = 0; j < blocksPerWord; j++) {
                    word |= ((blocks[idx++] & mask) << (j * bitsPerBlock));
                    if (idx > 4095) break;
                }
                writeLEInt(baos, word);
            }
            writeLEInt(baos, paletteSize);   // Write palette length
            NBTOutputStream stream = NbtUtils.createWriterLE(baos);
            for (int i = 0; i < paletteSize; i++) {
                CompoundTag ct = blockPalette.get(i);
                if (debug) {
                    System.out.println(ct.toString());
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    NBTOutputStream os = NbtUtils.createWriterLE(b);
                    os.write(ct);
                    os.close();
                    byte[] v = b.toByteArray();
                    for (int j = 0; j < v.length; j++) {
                        System.out.print(String.format("%02X:", v[j]));
                    }
                    System.out.println();
                }
                stream.write(ct);
            }
            stream.close();
            if (debug)
                System.out.println(String.format("out%d: sv=%d,bpb=%d,bpw=%d,wc=%d,m=%x,ps=%d", storeid, storageVersion, bitsPerBlock, blocksPerWord, wordCount, mask, paletteSize));

        }
        // Add full tag to palette
        public int addToPalette(CompoundTag blocktag) {
            blockPalette.add(blocktag);
            return blockPalette.size() - 1;
        }
        // Add new block to palette (we only add no state blocks, at this point)
        // @returns index of palette entry
        public int addToPalette(String blockname) {
            CompoundTagBuilder cbld = CompoundTagBuilder.builder();
            cbld.stringTag("name", blockname);
            CompoundTagBuilder cbld2 = CompoundTagBuilder.builder();
            cbld.tag(cbld2.build("states"));
            CompoundTag ctag = cbld.buildRootTag();
            return addToPalette(ctag);
        }
        // Find block in palette
        // @returns -1 if not found
        public int findInPalette(String blockname) {
            int len = blockPalette.size();
            for (int i = 0; i < len; i++) {
                CompoundTag v = (CompoundTag) blockPalette.get(i);
                if (v.getString("name").equals(blockname)) {
                    return i;
                }
            }
            return -1;
        }
        // Replace palette record
        public CompoundTag replaceInPalette(int index, CompoundTag newblk) {
            CompoundTag oldtag = blockPalette.get(index);
            blockPalette.set(index, newblk);
            return oldtag;
        }
        // Replace palette record
        public CompoundTag replaceInPalette(int index, String newblkid) {
            CompoundTagBuilder cbld = CompoundTagBuilder.builder();
            cbld.stringTag("name", newblkid);
            CompoundTagBuilder cbld2 = CompoundTagBuilder.builder();
            cbld.tag(cbld2.build("states"));
            CompoundTag ctag = cbld.buildRootTag();
            return replaceInPalette(index, ctag);
        }
        // Set block to block ID
        public void setBlockToBlockName(int bx, int by, int bz, String blkname) {
            int idx = findInPalette(blkname);
            if (idx < 0) {
                idx = addToPalette(blkname);
            }
            // XZY
            int off = ((bx & 0xF) << 8) | ((bz & 0xF) << 4) | (by & 0xF);
           // int prev = blocks[off];
           // System.out.println(String.format("set(%d,%d,%d,%s) - off=%03X, idx=%d, prev=%d", bx, by, bz, blkname, off, idx, prev));
            blocks[off] = idx;
        }
    };
    private StorageChunk[] chunks;

    public BedrockSubChunk(byte[] key, byte[] value, boolean debug) throws IOException {
        this.key = new byte[key.length];
        System.arraycopy(key, 0, this.key, 0, key.length);
        ByteBuffer bb = ByteBuffer.wrap(key);
        bb.order(ByteOrder.LITTLE_ENDIAN);
        cx = bb.getInt();
        cz = bb.getInt();
        int dim = 0;
        if (key.length == 14) {
            dim = bb.getInt();
        }
        this.dim = dim;
        int tagid = bb.get(); // Always 47 for now
        cy = bb.get();
        if (tagid != 47) {
            // Not SubChunk
            return;
        }
        if (value != null) {
            // Wrap value in bytebuffer        
            ByteArrayInputStream bais = new ByteArrayInputStream(value);
            ver = bais.read();
            if (ver != 8) {
                System.out.println("ver != 8: " + ver);
            }
            int storageCount = bais.read();
            // Creaste storage chunk for each
            chunks = new StorageChunk[storageCount];
            for (int sc = 0; sc < storageCount; sc++) {
                chunks[sc] = new StorageChunk();
                chunks[sc].loadFromInputStream(bais, sc, debug);
            }
        }
        else {
            chunks = new StorageChunk[1];
            chunks[0] = new StorageChunk();
            chunks[0].addToPalette("minecraft:air");
            ver = 8;
        }
        good = true;
    }

    public byte[] getKey() {
        return key;
    }

    public byte[] getValue() throws IOException {
        return getValue(false);
    }

    public byte[] getValue(boolean debug) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(ver);  // Always 8(?)
        baos.write(chunks.length);  // Number of chunks
        // Write each storage chunk
        for (int sc = 0; sc < chunks.length; sc++) {
            chunks[sc].writeToOutputStream(baos, sc, debug);
        }
        return baos.toByteArray();
    }

    public int getCX() { return cx; }
    public int getCY() { return cy; }
    public int getCZ() { return cz; }
    public int getDIM() { return dim; }
    public int getVER() { return ver; }
    public boolean isGood() { return good; }

    public StorageChunk getStorageChunk(int idx) {
        return chunks[idx];
    }
}
