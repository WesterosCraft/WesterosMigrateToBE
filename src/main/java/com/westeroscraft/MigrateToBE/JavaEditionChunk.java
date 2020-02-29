package com.westeroscraft.MigrateToBE;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;

import com.westeroscraft.MigrateToBE.nbt.tag.CompoundTag;

public class JavaEditionChunk {
    private static File basePath;
    private static File overworldRegionPath;
    private static HashMap<Integer, HashMap<Integer, HashMap<Integer, RegionFile>>> regionfiles = new HashMap<Integer, HashMap<Integer, HashMap<Integer, RegionFile>>>();

    private int cx, cz;
    private int dimension;

    public static void setBasePath(String path) {
        basePath = new File(path);
        overworldRegionPath = new File(basePath, "region");
    }

    private static RegionFile getRegionFile(int rx, int rz, int dim) throws IOException {
        HashMap<Integer, HashMap<Integer, RegionFile>> dimfiles = regionfiles.get(dim);
        if (dimfiles == null) {
            dimfiles = new HashMap<Integer, HashMap<Integer, RegionFile>>();
            regionfiles.put(dim, dimfiles);
        }
        HashMap<Integer, RegionFile> rowfiles = dimfiles.get(rx);
        if (rowfiles == null) {
            rowfiles = new HashMap<Integer, RegionFile>();
            dimfiles.put(rx, rowfiles);
        }
        RegionFile rf = rowfiles.get(rz);
        if (rf == null) {
            File rp;
            if (dim == 0)
                rp = overworldRegionPath;
            else
                rp = new File(basePath, String.format("DIM%d/region", dim));
            File rfile = new File(rp, String.format("r.%d.%d.mca", rx, rz));
            rf = new RegionFile(rfile);
            rowfiles.put(rz, rf);
        }        
        return rf;
    }

    // Constructor for chunk
    public JavaEditionChunk(int x, int z, int dim) {
        this.cx = x;
        this.cz = z;
        this.dimension = dim;
    }
    // Load chunk
    public boolean loadChunk() throws IOException {
        RegionFile rf = getRegionFile(cx >> 5, cz >> 5, dimension);
        int x = cx & 0x1F;
        int z = cz & 0x1F;
        if (rf.chunkExists(x, z)) {
            CompoundTag t = (CompoundTag) rf.readChunk(x, z);
            System.out.println(t.toString());
        }
        return true;
    }
}