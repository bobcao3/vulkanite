package me.cortex.vulkanite.lib.base;

import com.google.common.collect.ConcurrentHashMultiset;
import java.util.concurrent.ConcurrentLinkedDeque;
import me.cortex.vulkanite.lib.memory.VBuffer;

import java.util.*;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

public class VRegistry {
    public static final VRegistry INSTANCE = new VRegistry();

    private static class ObjectHeap {
        protected final long threadId = Thread.currentThread().getId();
        protected final String threadName = Thread.currentThread().getName();

        protected final HashSet<VObject> objects = new HashSet<>();
        protected final ConcurrentLinkedDeque<VObject> toFree = new ConcurrentLinkedDeque<>();

        protected void collect() {
            if (Thread.currentThread().getId() != threadId) {
                throw new IllegalStateException("Object heap collect called from wrong thread");
            }
            VObject object;
            while ((object = toFree.poll()) != null) {
                objects.remove(object);
                object.free();
            }
        }
    }

    private final ConcurrentHashMultiset<ObjectHeap> objectHeaps = ConcurrentHashMultiset.create();

    private final ThreadLocal<ObjectHeap> heap = ThreadLocal.withInitial(()->{
        var heap = new ObjectHeap();
        objectHeaps.add(heap);
        return heap;
    });

    private VRegistry() {
    }

    public void register(VObject object) {
        heap.get().objects.add(object);
        object.heap = heap.get();
    }

    public void unregister(VObject object) {
        ObjectHeap heap = (ObjectHeap) object.heap;
        heap.toFree.add(object);
    }

    private static final HashMap<Integer, String> usageNames = new HashMap<>() {{
        put(VK_BUFFER_USAGE_TRANSFER_DST_BIT, "TRANSFER_DST");
        put(VK_BUFFER_USAGE_TRANSFER_SRC_BIT, "TRANSFER_SRC");
        put(VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, "UNIFORM_BUFFER");
        put(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "STORAGE_BUFFER");
        put(VK_BUFFER_USAGE_INDEX_BUFFER_BIT, "INDEX_BUFFER");
        put(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, "VERTEX_BUFFER");
        put(VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, "SHADER_DEVICE_ADDRESS");
        put(VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR, "ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY");
        put(VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, "ACCELERATION_STRUCTURE_STORAGE");
        put(VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR, "SHADER_BINDING_TABLE");
    }};

    public String dumpStats() {
        final StringBuilder sb = new StringBuilder();

        ObjectHeap heap = this.heap.get();
        var objects = heap.objects;

        sb.append("\nVRegistry (Thread=").append(heap.threadId).append(" ").append(heap.threadName).append("): ");
        sb.append(objects.size()).append(" objects\n");
        sb.append("Objects:\n");

        Map<String, Integer> typeCount = new TreeMap<>();
        for (VObject object : objects) {
            var key = object.getClass().getTypeName();
            typeCount.put(key, typeCount.getOrDefault(key, 0) + 1);
        }

        for (String type : typeCount.keySet()) {
            sb.append("  ").append(type).append(": ").append(typeCount.get(type)).append("\n");
        }

        Map<Integer, Integer> bufferUsage = new TreeMap<>();
        for (VObject object : objects) {
            if (object instanceof VBuffer buffer) {
                var usage = buffer.usage();
                bufferUsage.put(usage, bufferUsage.getOrDefault(usage, 0) + 1);
            }
        }

        sb.append("Buffer Count Per Usage:\n");
        for (int usage : bufferUsage.keySet()) {
            sb.append("  ").append(usage).append(": ").append(bufferUsage.get(usage)).append("\n");
            sb.append("  (");
            usageNames.forEach((key, value) -> {
                if ((usage & key) != 0) {
                    sb.append(value).append(", ");
                }
            });
            sb.append(")\n");
        }

        return sb.toString();
    }

    public void threadLocalCollect() {
        heap.get().collect();
    }
}
