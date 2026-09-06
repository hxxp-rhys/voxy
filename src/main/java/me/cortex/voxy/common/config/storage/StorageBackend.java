package me.cortex.voxy.common.config.storage;

import me.cortex.voxy.common.config.IMappingStorage;
import me.cortex.voxy.common.config.IStoredSectionPositionIterator;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.ThreadLocalMemoryBuffer;

import java.util.ArrayList;
import java.util.List;

public abstract class StorageBackend implements IMappingStorage, IStoredSectionPositionIterator {

    //Implementation may use the scratch buffer as the return value, it MUST NOT free the scratch buffer
    public abstract MemoryBuffer getSectionData(long key, MemoryBuffer scratch);

    /**
     * Scratch used only by the default {@link #sectionExists} below. Held in a lazily
     * initialised holder class AND thread-local, so backends that override sectionExists
     * (every shipped backend can answer from the key alone) never allocate it at all,
     * which matters because the LOD resync verifier probes on its own thread and would
     * otherwise force a ~1 MiB native buffer into existence just to throw the value away.
     */
    private static final class ExistsScratch {
        static final ThreadLocalMemoryBuffer SCRATCH =
                new ThreadLocalMemoryBuffer(SectionSerializationStorage.BIGGEST_SERIALIZED_SECTION_SIZE + 1024);
    }

    /**
     * Existence probe: is a section persisted under this key?
     * <p>
     * The default answers via {@link #getSectionData}, correct for any backend for free,
     * but it copies the value into a private scratch (which is never handed out, so it
     * cannot leak or be freed by a caller); backends that can answer from the key alone
     * MUST override. Used by the LOD resync verifier (RMN), which probes thousands of
     * section keys per second and never looks at the payload.
     */
    public boolean sectionExists(long key) {
        return this.getSectionData(key, ExistsScratch.SCRATCH.get().createUntrackedUnfreeableReference()) != null;
    }

    public abstract void setSectionData(long key, MemoryBuffer data);

    public abstract void deleteSectionData(long key);

    public abstract void flush();

    public abstract void close();

    public List<StorageBackend> getChildBackends() {
        return List.of();
    }

    public final List<StorageBackend> collectAllBackends() {
        List<StorageBackend> backends = new ArrayList<>();
        backends.add(this);
        for (var child : this.getChildBackends()) {
            backends.addAll(child.collectAllBackends());
        }
        return backends;
    }
}
