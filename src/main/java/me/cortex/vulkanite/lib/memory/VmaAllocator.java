package me.cortex.vulkanite.lib.memory;

import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.lib.base.VObject;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.*;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;

import static me.cortex.vulkanite.lib.other.VUtil._CHECK_;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;

public class VmaAllocator {
    private final VkDevice device;
    private final long allocator;
    private final boolean hasDeviceAddresses;

    private final long sharedPool;
    private final long sharedDedicatedPool;

    private final long sharedBlockSize;

    private record ImageFormatQuery(int format, int imageType, int tiling, int usage, int flags) {}
    private record ImageFormatQueryResult(boolean supported, ImageFormatQuery updatedParams) {}
    private HashMap<ImageFormatQuery, ImageFormatQueryResult> formatSupportCache = new HashMap<>();

    private final VkExportMemoryAllocateInfo exportMemoryAllocateInfo;
    private final VkExportMemoryAllocateInfo exportDedicatedMemoryAllocateInfo;

    boolean testModifyFormatSupport(VkDevice device, VkImageCreateInfo imageCreateInfo) {
        var query = new ImageFormatQuery(imageCreateInfo.format(), imageCreateInfo.imageType(), imageCreateInfo.tiling(),
                imageCreateInfo.usage(), imageCreateInfo.flags());
        if (formatSupportCache.containsKey(query)) {
            var res = formatSupportCache.get(query);
            if (res.supported) {
                imageCreateInfo.format(res.updatedParams.format);
                imageCreateInfo.imageType(res.updatedParams.imageType);
                imageCreateInfo.tiling(res.updatedParams.tiling);
                imageCreateInfo.usage(res.updatedParams.usage);
                imageCreateInfo.flags(res.updatedParams.flags);
            }
            return res.supported;
        }

        try (var stack = stackPush()) {
            var pImageFormatProperties = VkImageFormatProperties.callocStack(stack);
            var result = vkGetPhysicalDeviceImageFormatProperties(device.getPhysicalDevice(), imageCreateInfo.format(),
                    imageCreateInfo.imageType(), imageCreateInfo.tiling(), imageCreateInfo.usage(),
                    imageCreateInfo.flags(), pImageFormatProperties);
            if (result == VK_SUCCESS) {
                formatSupportCache.put(query, new ImageFormatQueryResult(true, query));
                return true;
            } else if (result != VK_ERROR_FORMAT_NOT_SUPPORTED) {
                throw new RuntimeException("Failed to get image format properties");
            }

            // If storage image is set, try to un-set it
            if ((imageCreateInfo.usage() & VK_IMAGE_USAGE_STORAGE_BIT) != 0) {
                imageCreateInfo.usage(imageCreateInfo.usage() & ~VK_IMAGE_USAGE_STORAGE_BIT);
                boolean res = testModifyFormatSupport(device, imageCreateInfo);
                if (res) {
                    System.err.println("WARNING: Storage image usage was removed from " + imageCreateInfo.format() + " due to lack of support");
                }
                return res;
            }

            // If tiling is optimal, try linear
            if (imageCreateInfo.tiling() == VK_IMAGE_TILING_OPTIMAL) {
                imageCreateInfo.tiling(VK_IMAGE_TILING_LINEAR);
                boolean res = testModifyFormatSupport(device, imageCreateInfo);
                if (res) {
                    System.err.println("WARNING: TILING_OPTIMAL was changed to TILING_LINEAR " + imageCreateInfo.format() + " due to lack of support");
                }
                return res;
            }
        }

        formatSupportCache.put(query, new ImageFormatQueryResult(false, null));
        return false;
    }

    public VmaAllocator(VkDevice device, boolean enableDeviceAddresses, long sharedBlockSize, int sharedHandleType) {
        this.device = device;
        this.hasDeviceAddresses = enableDeviceAddresses;
        this.sharedBlockSize = sharedBlockSize;

        try (var stack = stackPush()) {
            VmaAllocatorCreateInfo allocatorCreateInfo = VmaAllocatorCreateInfo.calloc(stack)
                    .instance(device.getPhysicalDevice().getInstance())
                    .physicalDevice(device.getPhysicalDevice())
                    .device(device)
                    .pVulkanFunctions(VmaVulkanFunctions
                            .calloc(stack)
                            .set(device.getPhysicalDevice().getInstance(), device))
                    .vulkanApiVersion(VK_API_VERSION_1_2)
                    .flags(enableDeviceAddresses ? VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT : 0);
            allocatorCreateInfo.flags(allocatorCreateInfo.flags() | VMA_ALLOCATOR_CREATE_EXT_MEMORY_BUDGET_BIT);

            PointerBuffer pAllocator = stack.pointers(0);
            if (vmaCreateAllocator(allocatorCreateInfo, pAllocator) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create allocator");
            }

            allocator = pAllocator.get(0);

            {
                // Create the pool for dedicated allocation
                var imageCreateInfo = VkImageCreateInfo.calloc(stack)
                        .sType$Default()
                        .imageType(VK_IMAGE_TYPE_2D)
                        .format(VK_FORMAT_R8G8B8A8_UNORM)
                        .extent(e -> e.set(512, 512, 1))
                        .mipLevels(1)
                        .arrayLayers(1)
                        .samples(VK_SAMPLE_COUNT_1_BIT)
                        .tiling(VK_IMAGE_TILING_OPTIMAL)
                        .usage(VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT
                                | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT)
                        .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                        .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
                var allocationCreateInfo = VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE)
                        .requiredFlags(VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                IntBuffer pMemoryTypeIndex = stack.callocInt(1);
                if (vmaFindMemoryTypeIndexForImageInfo(allocator, imageCreateInfo, allocationCreateInfo,
                        pMemoryTypeIndex) != VK_SUCCESS) {
                    throw new RuntimeException("Failed to find memory type index for shared pool");
                }
                int sharedDedicatedPoolMemoryTypeIndex = pMemoryTypeIndex.get(0);

                exportDedicatedMemoryAllocateInfo = VkExportMemoryAllocateInfo.calloc()
                        .sType$Default()
                        .pNext(0)
                        .handleTypes(sharedHandleType);

                VmaPoolCreateInfo pci = VmaPoolCreateInfo.calloc(stack)
                        .memoryTypeIndex(sharedDedicatedPoolMemoryTypeIndex)
                        .pMemoryAllocateNext(exportDedicatedMemoryAllocateInfo.address());
                PointerBuffer pb = stack.callocPointer(1);
                if (vmaCreatePool(allocator, pci, pb) != VK_SUCCESS) {
                    throw new RuntimeException("Failed to create sharedDedicatedPool");
                }
                sharedDedicatedPool = pb.get(0);
            }

            {
                var bufferCreateInfo = VkBufferCreateInfo.calloc(stack)
                        .sType$Default()
                        .size(sharedBlockSize)
                        .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT
                                | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT)
                        .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
                var allocationCreateInfo = VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_UNKNOWN)
                        .requiredFlags(VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                IntBuffer pMemoryTypeIndex = stack.callocInt(1);
                if (vmaFindMemoryTypeIndexForBufferInfo(allocator, bufferCreateInfo, allocationCreateInfo,
                        pMemoryTypeIndex) != VK_SUCCESS) {
                    throw new RuntimeException("Failed to find memory type index for shared pool");
                }

                int sharedPoolMemoryTypeIndex = pMemoryTypeIndex.get(0);

                exportMemoryAllocateInfo = VkExportMemoryAllocateInfo.calloc()
                        .sType$Default()
                        .pNext(0)
                        .handleTypes(sharedHandleType);

                VmaPoolCreateInfo pci = VmaPoolCreateInfo.calloc(stack)
                        .memoryTypeIndex(sharedPoolMemoryTypeIndex)
                        .blockSize(sharedBlockSize)
                        .pMemoryAllocateNext(exportMemoryAllocateInfo.address());
                PointerBuffer pb = stack.callocPointer(1);
                if (vmaCreatePool(allocator, pci, pb) != VK_SUCCESS) {
                    throw new RuntimeException("Failed to create sharedPool");
                }
                sharedPool = pb.get(0);
            }
        }
    }

    // NOTE: SHOULD ONLY BE USED TO ALLOCATE SHARED MEMORY AND STUFF, not
    // recommended
    SharedBufferAllocation allocShared(VkBufferCreateInfo bufferCreateInfo,
            VmaAllocationCreateInfo allocationCreateInfo) {
        try (var stack = stackPush()) {
            LongBuffer pb = stack.callocLong(1);
            _CHECK_(vkCreateBuffer(device, bufferCreateInfo, null, pb), "Failed to create VkBuffer");
            long buffer = pb.get(0);

            var memReq = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(device, buffer, memReq);
            allocationCreateInfo.memoryTypeBits(memReq.memoryTypeBits());

            boolean dedicated = memReq.size() > sharedBlockSize;

            if (!dedicated) {
                var dedicatedMemReq = VkMemoryDedicatedRequirements.calloc(stack)
                        .sType$Default()
                        .pNext(0);
                var memReq2 = VkMemoryRequirements2.calloc(stack)
                        .sType$Default()
                        .pNext(dedicatedMemReq.address());
                vkGetBufferMemoryRequirements2(device, VkBufferMemoryRequirementsInfo2
                        .calloc(stack)
                        .sType$Default()
                        .buffer(buffer), memReq2);
                dedicated = dedicatedMemReq.prefersDedicatedAllocation() || dedicatedMemReq.requiresDedicatedAllocation();
            }

            if (dedicated) {
                allocationCreateInfo.flags(VMA_ALLOCATION_CREATE_DEDICATED_MEMORY_BIT);
                allocationCreateInfo.pool(sharedDedicatedPool);
            } else {
                allocationCreateInfo.pool(sharedPool);
            }

            VmaAllocationInfo vai = VmaAllocationInfo.calloc();

            PointerBuffer pAllocation = stack.mallocPointer(1);
            _CHECK_(
                    vmaAllocateMemoryForBuffer(allocator, buffer, allocationCreateInfo, pAllocation, vai),
                    "Failed to allocate memory for buffer");
            long allocation = pAllocation.get(0);
            _CHECK_(vmaBindBufferMemory(allocator, allocation, buffer), "failed to bind buffer memory");

            boolean hasDeviceAddress = ((bufferCreateInfo.usage() & VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT) > 0);
            return new SharedBufferAllocation(buffer, allocation, vai, hasDeviceAddress,
                    dedicated);
        }
    }

    BufferAllocation alloc(long pool, VkBufferCreateInfo bufferCreateInfo,
            VmaAllocationCreateInfo allocationCreateInfo) {
        return alloc(pool, bufferCreateInfo, allocationCreateInfo, 0);
    }

    BufferAllocation alloc(long pool, VkBufferCreateInfo bufferCreateInfo, VmaAllocationCreateInfo allocationCreateInfo,
            long alignment) {
        if (bufferCreateInfo.size() == 0) {
            throw new RuntimeException("Buffer size must be greater than 0");
        }
        try (var stack = stackPush()) {
            LongBuffer pb = stack.mallocLong(1);
            PointerBuffer pa = stack.mallocPointer(1);
            VmaAllocationInfo vai = VmaAllocationInfo.calloc();
            _CHECK_(
                    vmaCreateBufferWithAlignment(allocator,
                            bufferCreateInfo,
                            allocationCreateInfo.pool(pool),
                            alignment,
                            pb,
                            pa,
                            vai),
                    "Failed to allocate buffer");
            return new BufferAllocation(pb.get(0), pa.get(0), vai,
                    (bufferCreateInfo.usage() & VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT) != 0);
        }
    }

    SharedImageAllocation allocShared(VkImageCreateInfo imageCreateInfo, VmaAllocationCreateInfo allocationCreateInfo) {
        testModifyFormatSupport(device, imageCreateInfo);
        try (var stack = stackPush()) {
            LongBuffer pb = stack.callocLong(1);
            _CHECK_(vkCreateImage(device, imageCreateInfo, null, pb), "Failed to create VkImage");
            long image = pb.get(0);

            var memReq = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(device, image, memReq);

            boolean dedicated = memReq.size() > sharedBlockSize;
            if (Vulkanite.INSTANCE.IS_ZINK) {
                dedicated = false;
            }

            if (!dedicated) {
                var dedicatedMemReq = VkMemoryDedicatedRequirements.calloc(stack)
                        .sType$Default()
                        .pNext(0);
                var memReq2 = VkMemoryRequirements2.calloc(stack)
                        .sType$Default()
                        .pNext(dedicatedMemReq.address());
                vkGetImageMemoryRequirements2(device, VkImageMemoryRequirementsInfo2
                        .calloc(stack)
                        .sType$Default()
                        .image(image), memReq2);
                if (Vulkanite.INSTANCE.IS_ZINK) {
                    if (dedicatedMemReq.requiresDedicatedAllocation()) {
                        throw new RuntimeException("Zink does not support importing dedicated memory, however the Vulkan implementation demands it");
                    }
                } else {
                    dedicated = dedicatedMemReq.prefersDedicatedAllocation() || dedicatedMemReq.requiresDedicatedAllocation();
                }
            }

            if (dedicated) {
                allocationCreateInfo.flags(VMA_ALLOCATION_CREATE_DEDICATED_MEMORY_BIT);
                allocationCreateInfo.pool(sharedDedicatedPool);
            } else {
                allocationCreateInfo.pool(sharedPool);
            }

            allocationCreateInfo.memoryTypeBits(memReq.memoryTypeBits());

            VmaAllocationInfo vai = VmaAllocationInfo.calloc();
            PointerBuffer pAllocation = stack.mallocPointer(1);
            _CHECK_(vmaAllocateMemoryForImage(allocator, image, allocationCreateInfo, pAllocation, vai), "Failed to allocate memory for image");

            long allocation = pAllocation.get(0);
            _CHECK_(vmaBindImageMemory(allocator, allocation, image), "failed to bind image memory");

            return new SharedImageAllocation(image, allocation, vai, dedicated);
        }
    }

    ImageAllocation alloc(long pool, VkImageCreateInfo imageCreateInfo, VmaAllocationCreateInfo allocationCreateInfo) {
        testModifyFormatSupport(device, imageCreateInfo);
        try (var stack = stackPush()) {
            LongBuffer pi = stack.mallocLong(1);
            PointerBuffer pa = stack.mallocPointer(1);
            VmaAllocationInfo vai = VmaAllocationInfo.calloc();
            _CHECK_(
                    vmaCreateImage(allocator,
                            imageCreateInfo,
                            allocationCreateInfo.pool(pool),
                            pi,
                            pa,
                            vai),
                    "Failed to allocate buffer");
            return new ImageAllocation(pi.get(0), pa.get(0), vai);
        }
    }

    public abstract static class Allocation extends VObject {
        public final VmaAllocationInfo ai;
        public final long allocation;

        public Allocation(long allocation, VmaAllocationInfo info) {

            this.ai = info;
            this.allocation = allocation;
        }

        protected void free() {
            // vmaFreeMemory(allocator, allocation);
            ai.free();
        }

        public long size() {
            return ai.size();
        }
    }

    public class BufferAllocation extends Allocation {
        public long buffer = 0;
        public final long deviceAddress;

        protected BufferAllocation(long buffer, long allocation, VmaAllocationInfo info, boolean hasDeviceAddress) {
            super(allocation, info);
            this.buffer = buffer;
            if (hasDeviceAddresses && hasDeviceAddress) {
                try (MemoryStack stack = stackPush()) {
                    deviceAddress = vkGetBufferDeviceAddress(device, VkBufferDeviceAddressInfo
                            .calloc(stack)
                            .sType$Default()
                            .buffer(buffer));
                }
            } else {
                deviceAddress = -1;
            }
        }

        @Override
        protected void free() {
            // vkFreeMemory();
            vmaDestroyBuffer(allocator, buffer, allocation);
            super.free();
        }

        public VkDevice getDevice() {
            return device;
        }

        // TODO: Maybe put the following 3 in VBuffer
        public long map() {
            try (var stack = stackPush()) {
                PointerBuffer res = stack.callocPointer(1);
                _CHECK_(vmaMapMemory(allocator, allocation, res));
                return res.get(0);
            }
        }

        public void unmap() {
            vmaUnmapMemory(allocator, allocation);
        }

        public void flush(long offset, long size) {
            // TODO: offset must be a multiple of
            // VkPhysicalDeviceLimits::nonCoherentAtomSize
            /*
             * _CHECK_(vkFlushMappedMemoryRanges(device, VkMappedMemoryRange
             * .calloc(stack)
             * .sType$Default()
             * .memory(ai.deviceMemory())
             * .size(size)
             * .offset(ai.offset()+offset)));
             */
            vmaFlushAllocation(allocator, allocation, offset, size);
        }

    }

    public class SharedBufferAllocation extends BufferAllocation {
        private final boolean dedicated;

        protected SharedBufferAllocation(long buffer, long allocation, VmaAllocationInfo info, boolean hasDeviceAddress,
                boolean dedicated) {
            super(buffer, allocation, info, hasDeviceAddress);
            this.dedicated = dedicated;
        }

        @Override
        protected void free() {
            vkDestroyBuffer(device, buffer, null);
            vmaFreeMemory(allocator, allocation);
            ai.free();
        }

        boolean isDedicated() {
            return dedicated;
        }
    }

    public class ImageAllocation extends Allocation {
        public final long image;

        protected ImageAllocation(long image, long allocation, VmaAllocationInfo info) {
            super(allocation, info);
            this.image = image;
        }

        @Override
        protected void free() {
            // vkFreeMemory();
            vmaDestroyImage(allocator, image, allocation);
            super.free();
        }
    }

    public class SharedImageAllocation extends ImageAllocation {
        private final boolean dedicated;

        protected SharedImageAllocation(long image, long allocation, VmaAllocationInfo info, boolean dedicated) {
            super(image, allocation, info);
            this.dedicated = dedicated;
        }

        @Override
        protected void free() {
            // vkFreeMemory();
            vkDestroyImage(device, image, null);
            vmaFreeMemory(allocator, allocation);
            ai.free();
        }

        boolean isDedicated() {
            return dedicated;
        }
    }

    public String dumpJson(boolean detailed) {
        try (var stack = stackPush()) {
            PointerBuffer pb = stack.callocPointer(1);
            vmaBuildStatsString(allocator, pb, detailed);
            String result = MemoryUtil.memUTF8(pb.get(0));
            nvmaFreeStatsString(allocator, pb.get(0));
            return result;
        }
    }
}
