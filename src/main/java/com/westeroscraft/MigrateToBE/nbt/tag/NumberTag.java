package com.westeroscraft.MigrateToBE.nbt.tag;

public abstract class NumberTag<T extends Number> extends Tag<T> {

    NumberTag(String name) {
        super(name);
    }
}
