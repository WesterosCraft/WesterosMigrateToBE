package com.westeroscraft.MigrateToBE;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

import com.nukkitx.nbt.CompoundTagBuilder;
import com.nukkitx.nbt.NbtUtils;
import com.nukkitx.nbt.stream.NBTInputStream;
import com.nukkitx.nbt.stream.NBTOutputStream;
import com.nukkitx.nbt.tag.CompoundTag;
import com.nukkitx.nbt.tag.Tag;

public class BedrockSubChunk {
    private final int cx, cy, cz;
    private final int dim;
    private final int tag;
    private final byte[] key;
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

    private class StorageChunk {
        private ArrayList<Tag<?>> blockPalette = new ArrayList<Tag<?>>();
        // Ordered (X*256) + (Z*16) + Y
        private int[] blocks = new int[4096];

        void loadFromInputStream(InputStream bais) throws IOException {
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
                Tag<?> ptag = stream.readTag();
                //System.out.println(ptag.toString());
                blockPalette.add(ptag);
            }
        }
        void writeToOutputStream(OutputStream baos) throws IOException {
            int paletteCnt = blockPalette.size();   // Get count
            if (paletteCnt == 0) {
                addToPalette("minecraft:air");
                paletteCnt = blockPalette.size();
            }
            int bitsPerBlock = 16;
            for (int i = 0; i < keySizes.length; i++) {
                if (paletteCnt <= (1 << keySizes[i])) {
                    bitsPerBlock = keySizes[i];
                    break;
                }
            }
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
            writeLEInt(baos, paletteCnt);   // Write palette length
            NbtUtils.createWriterLE(baos);
            NBTOutputStream stream = NbtUtils.createWriterLE(baos);
            for (int i = 0; i < paletteCnt; i++) {
                stream.write(blockPalette.get(i));
            }
        }
        // Add new block to palette (we only add no state blocks, at this point)
        int addToPalette(String blockname) {
            CompoundTagBuilder cbld = CompoundTagBuilder.builder();
            cbld.stringTag("name", blockname);
            CompoundTagBuilder cbld2 = CompoundTagBuilder.builder();
            cbld.tag(cbld2.build("states"));
            CompoundTag ctag = cbld.buildRootTag();
            blockPalette.add(ctag);
            return blockPalette.size() - 1;
        }
    };
    private StorageChunk[] chunks;

    public BedrockSubChunk(byte[] key, byte[] value) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(key);
        bb.order(ByteOrder.LITTLE_ENDIAN);
        cx = bb.getInt();
        cz = bb.getInt();
        int dim = 0;
        if (key.length == 14) {
            dim = bb.getInt();
        }
        this.dim = dim;
        tag = bb.get(); // Always 47 for now
        cy = bb.get();
        this.key = new byte[key.length];
        System.arraycopy(key, 0, this.key, 0, key.length);        
        // Wrap value in bytebuffer        
        ByteArrayInputStream bais = new ByteArrayInputStream(value);
        int ver = bais.read();
        if (ver != 8) {
            System.out.println("ver != 8");
        }
        int storageCount = bais.read();
        // Creaste storage chunk for each
        chunks = new StorageChunk[storageCount];
        for (int sc = 0; sc < storageCount; sc++) {
            chunks[sc] = new StorageChunk();
            chunks[sc].loadFromInputStream(bais);
        }
    }

    public byte[] getKey() {
        return key;
    }

    public byte[] getValue() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(8);  // Always 8
        baos.write(chunks.length);  // Number of chunks
        // Write each storage chunk
        for (int sc = 0; sc < chunks.length; sc++) {
            chunks[sc].writeToOutputStream(baos);
        }
        return baos.toByteArray();
    }

    public int getCX() { return cx; }
    public int getCY() { return cy; }
    public int getCZ() { return cz; }
    public int getDIM() { return dim; }
}
