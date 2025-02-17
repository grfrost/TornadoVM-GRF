/*
 * This file is part of Tornado: A heterogeneous programming framework: 
 * https://github.com/beehive-lab/tornadovm
 *
 * Copyright (c) 2020, APT Group, Department of Computer Science,
 * School of Engineering, The University of Manchester. All rights reserved.
 * Copyright (c) 2013-2020, APT Group, Department of Computer Science,
 * The University of Manchester. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Authors: James Clarkson
 *
 */
package uk.ac.manchester.tornado.drivers.opencl;

import static uk.ac.manchester.tornado.drivers.opencl.OCLCommandQueue.EMPTY_EVENT;
import static uk.ac.manchester.tornado.drivers.opencl.OCLEvent.DEFAULT_TAG;
import static uk.ac.manchester.tornado.drivers.opencl.OCLEvent.DESC_PARALLEL_KERNEL;
import static uk.ac.manchester.tornado.drivers.opencl.OCLEvent.DESC_READ_BYTE;
import static uk.ac.manchester.tornado.drivers.opencl.OCLEvent.DESC_READ_DOUBLE;
import static uk.ac.manchester.tornado.drivers.opencl.OCLEvent.DESC_READ_FLOAT;
import static uk.ac.manchester.tornado.drivers.opencl.OCLEvent.DESC_READ_INT;
import static uk.ac.manchester.tornado.drivers.opencl.OCLEvent.DESC_READ_LONG;
import static uk.ac.manchester.tornado.drivers.opencl.OCLEvent.DESC_READ_SHORT;
import static uk.ac.manchester.tornado.drivers.opencl.OCLEvent.DESC_SERIAL_KERNEL;
import static uk.ac.manchester.tornado.drivers.opencl.OCLEvent.DESC_SYNC_BARRIER;
import static uk.ac.manchester.tornado.drivers.opencl.OCLEvent.DESC_SYNC_MARKER;
import static uk.ac.manchester.tornado.drivers.opencl.OCLEvent.DESC_WRITE_BYTE;
import static uk.ac.manchester.tornado.drivers.opencl.OCLEvent.DESC_WRITE_DOUBLE;
import static uk.ac.manchester.tornado.drivers.opencl.OCLEvent.DESC_WRITE_FLOAT;
import static uk.ac.manchester.tornado.drivers.opencl.OCLEvent.DESC_WRITE_INT;
import static uk.ac.manchester.tornado.drivers.opencl.OCLEvent.DESC_WRITE_LONG;
import static uk.ac.manchester.tornado.drivers.opencl.OCLEvent.DESC_WRITE_SHORT;
import static uk.ac.manchester.tornado.runtime.common.Tornado.USE_SYNC_FLUSH;
import static uk.ac.manchester.tornado.runtime.common.Tornado.getProperty;

import java.nio.ByteOrder;
import java.util.Comparator;
import java.util.List;

import uk.ac.manchester.tornado.api.TornadoDeviceContext;
import uk.ac.manchester.tornado.api.common.Event;
import uk.ac.manchester.tornado.api.common.SchedulableTask;
import uk.ac.manchester.tornado.api.runtime.TornadoRuntime;
import uk.ac.manchester.tornado.drivers.opencl.enums.OCLDeviceType;
import uk.ac.manchester.tornado.drivers.opencl.enums.OCLMemFlags;
import uk.ac.manchester.tornado.drivers.opencl.graal.OCLInstalledCode;
import uk.ac.manchester.tornado.drivers.opencl.graal.compiler.OCLCompilationResult;
import uk.ac.manchester.tornado.drivers.opencl.mm.OCLMemoryManager;
import uk.ac.manchester.tornado.drivers.opencl.runtime.OCLTornadoDevice;
import uk.ac.manchester.tornado.runtime.common.Initialisable;
import uk.ac.manchester.tornado.runtime.common.Tornado;
import uk.ac.manchester.tornado.runtime.common.TornadoLogger;
import uk.ac.manchester.tornado.runtime.tasks.meta.TaskMetaData;

public class OCLDeviceContext extends TornadoLogger implements Initialisable, OCLDeviceContextInterface {

    private static final long BUMP_BUFFER_SIZE = Long.decode(getProperty("tornado.opencl.bump.size", "0x100000"));
    private static final String[] BUMP_DEVICES = parseDevices(getProperty("tornado.opencl.bump.devices", "Iris Pro"));
    private static final boolean PRINT_OCL_KERNEL_TIME = Boolean.parseBoolean(getProperty("tornado.opencl.timer.kernel", "False").toLowerCase());

    private final OCLTargetDevice device;
    private final OCLCommandQueue queue;
    private final OCLContext context;
    private final OCLMemoryManager memoryManager;
    private boolean needsBump;
    private final long bumpBuffer;

    private final OCLCodeCache codeCache;
    private boolean wasReset;
    private boolean useRelativeAddresses;
    private boolean printOnce = true;

    private final OCLEventsWrapper eventsWrapper;

    protected OCLDeviceContext(OCLTargetDevice device, OCLCommandQueue queue, OCLContext context) {
        this.device = device;
        this.queue = queue;
        this.context = context;
        this.memoryManager = new OCLMemoryManager(this);
        this.codeCache = new OCLCodeCache(this);

        setRelativeAddressesFlag();

        this.eventsWrapper = new OCLEventsWrapper();

        needsBump = false;
        for (String bumpDevice : BUMP_DEVICES) {
            if (device.getDeviceName().equalsIgnoreCase(bumpDevice.trim())) {
                needsBump = true;
                break;
            }
        }

        if (needsBump) {
            bumpBuffer = context.createBuffer(OCLMemFlags.CL_MEM_READ_WRITE, BUMP_BUFFER_SIZE);
            info("device requires bump buffer: %s", device.getDeviceName());
        } else {
            bumpBuffer = -1;
        }
    }

    private void setRelativeAddressesFlag() {
        if (isPlatformFPGA() && !Tornado.OPENCL_USE_RELATIVE_ADDRESSES) {
            useRelativeAddresses = true;
        } else {
            useRelativeAddresses = Tornado.OPENCL_USE_RELATIVE_ADDRESSES;
        }
    }

    private static String[] parseDevices(String str) {
        return str.split(";");
    }

    public OCLTargetDevice getDevice() {
        return device;
    }

    @Override
    public String toString() {
        return String.format("[%d] %s", getDevice().getIndex(), getDevice().getDeviceName());
    }

    @Override
    public String getDeviceName() {
        return String.format(device.getDeviceName());
    }

    @Override
    public int getDriverIndex() {
        return TornadoRuntime.getTornadoRuntime().getDriverIndex(OCLDriver.class);
    }

    public OCLContext getPlatformContext() {
        return context;
    }

    @Override
    public OCLMemoryManager getMemoryManager() {
        return memoryManager;
    }

    public void sync() {
        if (USE_SYNC_FLUSH) {
            queue.flush();
        }
        queue.finish();
    }

    public long getDeviceId() {
        return device.getId();
    }

    public int enqueueBarrier() {
        long oclEvent = queue.enqueueBarrier();
        return (queue.getOpenclVersion() < 120) ? -1 : eventsWrapper.registerEvent(oclEvent, DESC_SYNC_BARRIER, DEFAULT_TAG, queue);
    }

    public int enqueueMarker() {
        long oclEvent = queue.enqueueMarker();
        return queue.getOpenclVersion() < 120 ? -1 : eventsWrapper.registerEvent(oclEvent, DESC_SYNC_MARKER, DEFAULT_TAG, queue);
    }

    public OCLProgram createProgramWithSource(byte[] source, long[] lengths) {
        return context.createProgramWithSource(source, lengths, this);
    }

    public OCLProgram createProgramWithBinary(byte[] binary, long[] lengths) {
        return context.createProgramWithBinary(device.getId(), binary, lengths, this);
    }

    public void printEvents() {
        queue.printEvents();
    }

    public int enqueueTask(OCLKernel kernel, int[] events) {
        return eventsWrapper.registerEvent(queue.enqueueTask(kernel, eventsWrapper.serialiseEvents(events, queue) ? eventsWrapper.waitEventsBuffer : null), DESC_SERIAL_KERNEL, kernel.getOclKernelID(),
                queue);
    }

    public int enqueueTask(OCLKernel kernel) {
        return eventsWrapper.registerEvent(queue.enqueueTask(kernel, null), DESC_SERIAL_KERNEL, kernel.getOclKernelID(), queue);
    }

    public int enqueueNDRangeKernel(OCLKernel kernel, int dim, long[] globalWorkOffset, long[] globalWorkSize, long[] localWorkSize, int[] waitEvents) {
        return eventsWrapper.registerEvent(
                queue.enqueueNDRangeKernel(kernel, dim, globalWorkOffset, globalWorkSize, localWorkSize, eventsWrapper.serialiseEvents(waitEvents, queue) ? eventsWrapper.waitEventsBuffer : null),
                DESC_PARALLEL_KERNEL, kernel.getOclKernelID(), queue);
    }

    public ByteOrder getByteOrder() {
        return device.isLittleEndian() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
    }

    /*
     * Asynchronous writes to device
     */
    public int enqueueWriteBuffer(long bufferId, long offset, long bytes, byte[] array, long hostOffset, int[] waitEvents) {
        return eventsWrapper.registerEvent(
                queue.enqueueWrite(bufferId, OpenCLBlocking.FALSE, offset, bytes, array, hostOffset, eventsWrapper.serialiseEvents(waitEvents, queue) ? eventsWrapper.waitEventsBuffer : null),
                DESC_WRITE_BYTE, offset, queue);
    }

    public int enqueueWriteBuffer(long bufferId, long offset, long bytes, char[] array, long hostOffset, int[] waitEvents) {
        return eventsWrapper.registerEvent(
                queue.enqueueWrite(bufferId, OpenCLBlocking.FALSE, offset, bytes, array, hostOffset, eventsWrapper.serialiseEvents(waitEvents, queue) ? eventsWrapper.waitEventsBuffer : null),
                DESC_WRITE_BYTE, offset, queue);
    }

    public int enqueueWriteBuffer(long bufferId, long offset, long bytes, int[] array, long hostOffset, int[] waitEvents) {
        return eventsWrapper.registerEvent(
                queue.enqueueWrite(bufferId, OpenCLBlocking.FALSE, offset, bytes, array, hostOffset, eventsWrapper.serialiseEvents(waitEvents, queue) ? eventsWrapper.waitEventsBuffer : null),
                DESC_WRITE_INT, offset, queue);
    }

    public int enqueueWriteBuffer(long bufferId, long offset, long bytes, long[] array, long hostOffset, int[] waitEvents) {
        return eventsWrapper.registerEvent(
                queue.enqueueWrite(bufferId, OpenCLBlocking.FALSE, offset, bytes, array, hostOffset, eventsWrapper.serialiseEvents(waitEvents, queue) ? eventsWrapper.waitEventsBuffer : null),
                DESC_WRITE_LONG, offset, queue);
    }

    public int enqueueWriteBuffer(long bufferId, long offset, long bytes, short[] array, long hostOffset, int[] waitEvents) {
        return eventsWrapper.registerEvent(
                queue.enqueueWrite(bufferId, OpenCLBlocking.FALSE, offset, bytes, array, hostOffset, eventsWrapper.serialiseEvents(waitEvents, queue) ? eventsWrapper.waitEventsBuffer : null),
                DESC_WRITE_SHORT, offset, queue);
    }

    public int enqueueWriteBuffer(long bufferId, long offset, long bytes, float[] array, long hostOffset, int[] waitEvents) {
        return eventsWrapper.registerEvent(
                queue.enqueueWrite(bufferId, OpenCLBlocking.FALSE, offset, bytes, array, hostOffset, eventsWrapper.serialiseEvents(waitEvents, queue) ? eventsWrapper.waitEventsBuffer : null),
                DESC_WRITE_FLOAT, offset, queue);
    }

    public int enqueueWriteBuffer(long bufferId, long offset, long bytes, double[] array, long hostOffset, int[] waitEvents) {
        return eventsWrapper.registerEvent(
                queue.enqueueWrite(bufferId, OpenCLBlocking.FALSE, offset, bytes, array, hostOffset, eventsWrapper.serialiseEvents(waitEvents, queue) ? eventsWrapper.waitEventsBuffer : null),
                DESC_WRITE_DOUBLE, offset, queue);
    }

    /*
     * ASync reads from device
     *
     */
    public int enqueueReadBuffer(long bufferId, long offset, long bytes, byte[] array, long hostOffset, int[] waitEvents) {
        return eventsWrapper.registerEvent(
                queue.enqueueRead(bufferId, OpenCLBlocking.FALSE, offset, bytes, array, hostOffset, eventsWrapper.serialiseEvents(waitEvents, queue) ? eventsWrapper.waitEventsBuffer : null),
                DESC_READ_BYTE, offset, queue);
    }

    public int enqueueReadBuffer(long bufferId, long offset, long bytes, char[] array, long hostOffset, int[] waitEvents) {
        return eventsWrapper.registerEvent(
                queue.enqueueRead(bufferId, OpenCLBlocking.FALSE, offset, bytes, array, hostOffset, eventsWrapper.serialiseEvents(waitEvents, queue) ? eventsWrapper.waitEventsBuffer : null),
                DESC_READ_BYTE, offset, queue);
    }

    public int enqueueReadBuffer(long bufferId, long offset, long bytes, int[] array, long hostOffset, int[] waitEvents) {
        return eventsWrapper.registerEvent(
                queue.enqueueRead(bufferId, OpenCLBlocking.FALSE, offset, bytes, array, hostOffset, eventsWrapper.serialiseEvents(waitEvents, queue) ? eventsWrapper.waitEventsBuffer : null),
                DESC_READ_INT, offset, queue);
    }

    public int enqueueReadBuffer(long bufferId, long offset, long bytes, long[] array, long hostOffset, int[] waitEvents) {
        return eventsWrapper.registerEvent(
                queue.enqueueRead(bufferId, OpenCLBlocking.FALSE, offset, bytes, array, hostOffset, eventsWrapper.serialiseEvents(waitEvents, queue) ? eventsWrapper.waitEventsBuffer : null),
                DESC_READ_LONG, offset, queue);
    }

    public int enqueueReadBuffer(long bufferId, long offset, long bytes, float[] array, long hostOffset, int[] waitEvents) {
        return eventsWrapper.registerEvent(
                queue.enqueueRead(bufferId, OpenCLBlocking.FALSE, offset, bytes, array, hostOffset, eventsWrapper.serialiseEvents(waitEvents, queue) ? eventsWrapper.waitEventsBuffer : null),
                DESC_READ_FLOAT, offset, queue);
    }

    public int enqueueReadBuffer(long bufferId, long offset, long bytes, double[] array, long hostOffset, int[] waitEvents) {
        return eventsWrapper.registerEvent(
                queue.enqueueRead(bufferId, OpenCLBlocking.FALSE, offset, bytes, array, hostOffset, eventsWrapper.serialiseEvents(waitEvents, queue) ? eventsWrapper.waitEventsBuffer : null),
                DESC_READ_DOUBLE, offset, queue);
    }

    public int enqueueReadBuffer(long bufferId, long offset, long bytes, short[] array, long hostOffset, int[] waitEvents) {
        return eventsWrapper.registerEvent(
                queue.enqueueRead(bufferId, OpenCLBlocking.FALSE, offset, bytes, array, hostOffset, eventsWrapper.serialiseEvents(waitEvents, queue) ? eventsWrapper.waitEventsBuffer : null),
                DESC_READ_SHORT, offset, queue);
    }

    /*
     * Synchronous writes to device
     */
    public void writeBuffer(long bufferId, long offset, long bytes, byte[] array, long hostOffset, int[] waitEvents) {
        eventsWrapper.registerEvent(
                queue.enqueueWrite(bufferId, OpenCLBlocking.TRUE, offset, bytes, array, hostOffset, eventsWrapper.serialiseEvents(waitEvents, queue) ? eventsWrapper.waitEventsBuffer : null),
                DESC_WRITE_BYTE, offset, queue);
    }

    public void writeBuffer(long bufferId, long offset, long bytes, char[] array, long hostOffset, int[] waitEvents) {
        eventsWrapper.registerEvent(
                queue.enqueueWrite(bufferId, OpenCLBlocking.TRUE, offset, bytes, array, hostOffset, eventsWrapper.serialiseEvents(waitEvents, queue) ? eventsWrapper.waitEventsBuffer : null),
                DESC_WRITE_BYTE, offset, queue);
    }

    public void writeBuffer(long bufferId, long offset, long bytes, int[] array, long hostOffset, int[] waitEvents) {
        eventsWrapper.registerEvent(
                queue.enqueueWrite(bufferId, OpenCLBlocking.TRUE, offset, bytes, array, hostOffset, eventsWrapper.serialiseEvents(waitEvents, queue) ? eventsWrapper.waitEventsBuffer : null),
                DESC_WRITE_INT, offset, queue);
    }

    public void writeBuffer(long bufferId, long offset, long bytes, long[] array, long hostOffset, int[] waitEvents) {
        eventsWrapper.registerEvent(
                queue.enqueueWrite(bufferId, OpenCLBlocking.TRUE, offset, bytes, array, hostOffset, eventsWrapper.serialiseEvents(waitEvents, queue) ? eventsWrapper.waitEventsBuffer : null),
                DESC_WRITE_LONG, offset, queue);
    }

    public void writeBuffer(long bufferId, long offset, long bytes, short[] array, long hostOffset, int[] waitEvents) {
        eventsWrapper.registerEvent(
                queue.enqueueWrite(bufferId, OpenCLBlocking.TRUE, offset, bytes, array, hostOffset, eventsWrapper.serialiseEvents(waitEvents, queue) ? eventsWrapper.waitEventsBuffer : null),
                DESC_WRITE_SHORT, offset, queue);
    }

    public void writeBuffer(long bufferId, long offset, long bytes, float[] array, long hostOffset, int[] waitEvents) {
        eventsWrapper.registerEvent(
                queue.enqueueWrite(bufferId, OpenCLBlocking.TRUE, offset, bytes, array, hostOffset, eventsWrapper.serialiseEvents(waitEvents, queue) ? eventsWrapper.waitEventsBuffer : null),
                DESC_WRITE_FLOAT, offset, queue);
    }

    public void writeBuffer(long bufferId, long offset, long bytes, double[] array, long hostOffset, int[] waitEvents) {
        eventsWrapper.registerEvent(
                queue.enqueueWrite(bufferId, OpenCLBlocking.TRUE, offset, bytes, array, hostOffset, eventsWrapper.serialiseEvents(waitEvents, queue) ? eventsWrapper.waitEventsBuffer : null),
                DESC_WRITE_DOUBLE, offset, queue);
    }

    /*
     * Synchronous reads from device
     */
    public int readBuffer(long bufferId, long offset, long bytes, byte[] array, long hostOffset, int[] waitEvents) {
        return eventsWrapper.registerEvent(
                queue.enqueueRead(bufferId, OpenCLBlocking.TRUE, offset, bytes, array, hostOffset, eventsWrapper.serialiseEvents(waitEvents, queue) ? eventsWrapper.waitEventsBuffer : null),
                DESC_READ_BYTE, offset, queue);
    }

    public int readBuffer(long bufferId, long offset, long bytes, char[] array, long hostOffset, int[] waitEvents) {
        return eventsWrapper.registerEvent(
                queue.enqueueRead(bufferId, OpenCLBlocking.TRUE, offset, bytes, array, hostOffset, eventsWrapper.serialiseEvents(waitEvents, queue) ? eventsWrapper.waitEventsBuffer : null),
                DESC_READ_BYTE, offset, queue);
    }

    public int readBuffer(long bufferId, long offset, long bytes, int[] array, long hostOffset, int[] waitEvents) {
        return eventsWrapper.registerEvent(
                queue.enqueueRead(bufferId, OpenCLBlocking.TRUE, offset, bytes, array, hostOffset, eventsWrapper.serialiseEvents(waitEvents, queue) ? eventsWrapper.waitEventsBuffer : null),
                DESC_READ_INT, offset, queue);
    }

    public int readBuffer(long bufferId, long offset, long bytes, long[] array, long hostOffset, int[] waitEvents) {
        return eventsWrapper.registerEvent(
                queue.enqueueRead(bufferId, OpenCLBlocking.TRUE, offset, bytes, array, hostOffset, eventsWrapper.serialiseEvents(waitEvents, queue) ? eventsWrapper.waitEventsBuffer : null),
                DESC_READ_LONG, offset, queue);
    }

    public int readBuffer(long bufferId, long offset, long bytes, float[] array, long hostOffset, int[] waitEvents) {
        return eventsWrapper.registerEvent(
                queue.enqueueRead(bufferId, OpenCLBlocking.TRUE, offset, bytes, array, hostOffset, eventsWrapper.serialiseEvents(waitEvents, queue) ? eventsWrapper.waitEventsBuffer : null),
                DESC_READ_FLOAT, offset, queue);
    }

    public int readBuffer(long bufferId, long offset, long bytes, double[] array, long hostOffset, int[] waitEvents) {
        return eventsWrapper.registerEvent(
                queue.enqueueRead(bufferId, OpenCLBlocking.TRUE, offset, bytes, array, hostOffset, eventsWrapper.serialiseEvents(waitEvents, queue) ? eventsWrapper.waitEventsBuffer : null),
                DESC_READ_DOUBLE, offset, queue);

    }

    public int readBuffer(long bufferId, long offset, long bytes, short[] array, long hostOffset, int[] waitEvents) {
        return eventsWrapper.registerEvent(
                queue.enqueueRead(bufferId, OpenCLBlocking.TRUE, offset, bytes, array, hostOffset, eventsWrapper.serialiseEvents(waitEvents, queue) ? eventsWrapper.waitEventsBuffer : null),
                DESC_READ_SHORT, offset, queue);
    }

    public int enqueueBarrier(int[] events) {
        long oclEvent = queue.enqueueBarrier(eventsWrapper.serialiseEvents(events, queue) ? eventsWrapper.waitEventsBuffer : null);
        return queue.getOpenclVersion() < 120 ? -1 : eventsWrapper.registerEvent(oclEvent, DESC_SYNC_BARRIER, DEFAULT_TAG, queue);
    }

    public int enqueueMarker(int[] events) {
        long oclEvent = queue.enqueueMarker(eventsWrapper.serialiseEvents(events, queue) ? eventsWrapper.waitEventsBuffer : null);
        return queue.getOpenclVersion() < 120 ? -1 : eventsWrapper.registerEvent(oclEvent, DESC_SYNC_MARKER, DEFAULT_TAG, queue);
    }

    @Override
    public boolean isInitialised() {
        return memoryManager.isInitialised();
    }

    public void reset() {
        eventsWrapper.reset();
        memoryManager.reset();
        codeCache.reset();
        wasReset = true;
    }

    public OCLTornadoDevice asMapping() {
        return new OCLTornadoDevice(context.getPlatformIndex(), device.getIndex());
    }

    public String getId() {
        return String.format("opencl-%d-%d", context.getPlatformIndex(), device.getIndex());
    }

    public void dumpEvents() {
        List<OCLEvent> events = eventsWrapper.getEvents();

        final String deviceName = "opencl-" + context.getPlatformIndex() + "-" + device.getIndex();
        System.out.printf("Found %d events on device %s:\n", events.size(), deviceName);
        if (events.isEmpty()) {
            return;
        }

        events.sort(Comparator.comparingLong(OCLEvent::getCLSubmitTime).thenComparingLong(OCLEvent::getCLStartTime));

        long base = events.get(0).getCLSubmitTime();
        System.out.println("event: device,type,info,queued,submitted,start,end,status");
        events.forEach((e) -> {
            System.out.printf("event: %s,%s,0x%x,%d,%d,%d,%s\n", deviceName, e.getName(), e.getOclEventID(), e.getCLQueuedTime() - base, e.getCLSubmitTime() - base, e.getCLStartTime() - base,
                    e.getCLEndTime() - base, e.getStatus());
        });
    }

    @Override
    public boolean needsBump() {
        return needsBump;
    }

    @Override
    public boolean wasReset() {
        return wasReset;
    }

    @Override
    public void setResetToFalse() {
        wasReset = false;
    }

    @Override
    public boolean isPlatformFPGA() {
        return getDevice().getDeviceType() == OCLDeviceType.CL_DEVICE_TYPE_ACCELERATOR
                && (getPlatformContext().getPlatform().getName().toLowerCase().contains("fpga") || getPlatformContext().getPlatform().getName().toLowerCase().contains("xilinx"));
    }

    @Override
    public boolean useRelativeAddresses() {
        if (isPlatformFPGA() && !Tornado.OPENCL_USE_RELATIVE_ADDRESSES && printOnce) {
            System.out.println("Warning: -Dtornado.opencl.userelative was set to False. TornadoVM changed it to True because it is required for FPGA execution.");
            printOnce = false;
        }

        return useRelativeAddresses;
    }

    @Override
    public int getDeviceIndex() {
        return device.getIndex();
    }

    @Override
    public int getDevicePlatform() {
        return context.getPlatformIndex();
    }

    public long getBumpBuffer() {
        return bumpBuffer;
    }

    public void retainEvent(int localEventId) {
        eventsWrapper.retainEvent(localEventId);
    }

    public Event resolveEvent(int event) {
        if (event == -1) {
            return EMPTY_EVENT;
        }
        return new OCLEvent(eventsWrapper, queue, event, eventsWrapper.getOCLEvent(event));
    }

    public void flush() {
        queue.flush();
    }

    public void finish() {
        queue.finish();
    }

    public void flushEvents() {
        queue.flushEvents();
    }

    public boolean isKernelAvailable() {
        return codeCache.isKernelAvailable();
    }

    public OCLInstalledCode installCode(OCLCompilationResult result) {
        return installCode(result.getMeta(), result.getId(), result.getName(), result.getTargetCode());
    }

    public OCLInstalledCode installCode(TaskMetaData meta, String id, String entryPoint, byte[] code) {
        return codeCache.installSource(meta, id, entryPoint, code);
    }

    public OCLInstalledCode installCode(String id, String entryPoint, byte[] code, boolean shouldCompile) {
        return codeCache.installFPGASource(id, entryPoint, code, shouldCompile);
    }

    public boolean isCached(String id, String entryPoint) {
        return codeCache.isCached(id + "-" + entryPoint);
    }

    @Override
    public boolean isCached(String methodName, SchedulableTask task) {
        return codeCache.isCached(task.getId() + "-" + methodName);
    }

    public OCLInstalledCode getInstalledCode(String id, String entryPoint) {
        return codeCache.getInstalledCode(id, entryPoint);
    }

    public OCLCodeCache getCodeCache() {
        return this.codeCache;
    }
}
