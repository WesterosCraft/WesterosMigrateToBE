package com.westeroscraft.MigrateToBE.nbt.tag;

import java.util.Objects;

public class IntTag extends NumberTag<Integer> {
    private final int value;

    public IntTag(String name, Integer value) {
        super(name);
        this.value = value;
    }

    public int getPrimitiveValue() {
        return value;
    }

    @Override
    public Integer getValue() {
        return value;
    }

    @Override
    public int hashCode() {
        return Objects.hash(getName(), value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        IntTag that = (IntTag) o;
        return value == that.value &&
                Objects.equals(getName(), that.getName());
    }

    @Override
    public String toString() {
        return "TAG_Int" + super.toString() + value;
    }
}
