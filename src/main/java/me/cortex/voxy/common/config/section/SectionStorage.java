package me.cortex.voxy.common.config.section;

import me.cortex.voxy.common.config.IMappingStorage;
import me.cortex.voxy.common.config.IStoredSectionPositionIterator;
import me.cortex.voxy.common.world.WorldSection;

public abstract class SectionStorage implements IMappingStorage, IStoredSectionPositionIterator {
    public abstract int loadSection(WorldSection into);

    public abstract void saveSection(WorldSection section);

    /**
     * Cheap existence probe: is a section persisted under this key?
     * <p>
     * Deliberately NOT expressed as {@code WorldEngine.acquireIfExists(...) != null}: that
     * path materialises a WorldSection (a 32x32x32 long[] = 256 KiB), runs the deserializer
     * or an {@code Arrays.fill} of the whole array for an absent key, inserts a holder
     * into the active-section cache and then pushes the result through the LRU
     * secondary cache, evicting genuinely useful sections. A verifier that probes
     * thousands of columns per second must not do any of that.
     * <p>
     * Abstract rather than defaulted so a new storage cannot silently report
     * "everything is missing" (which would turn the LOD resync verifier into a
     * request flood). {@link SectionSerializationStorage} forwards to
     * {@code StorageBackend.sectionExists(long)}, a key-only lookup.
     */
    public abstract boolean sectionExists(long key);
}
