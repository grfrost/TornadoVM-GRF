/*
 * This file is part of Tornado: A heterogeneous programming framework: 
 * https://github.com/beehive-lab/tornado
 *
 * Copyright (c) 2013-2019, APT Group, School of Computer Science,
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
package uk.ac.manchester.tornado.runtime.tasks.meta;

import static uk.ac.manchester.tornado.api.exceptions.TornadoInternalError.guarantee;
import static uk.ac.manchester.tornado.runtime.common.Tornado.EVENT_WINDOW;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import uk.ac.manchester.tornado.api.common.Access;
import uk.ac.manchester.tornado.api.common.TornadoEvents;
import uk.ac.manchester.tornado.runtime.EventSet;
import uk.ac.manchester.tornado.runtime.common.TornadoAcceleratorDevice;
import uk.ac.manchester.tornado.runtime.domain.DomainTree;

public class TaskMetaData extends AbstractMetaData {

    private String idTask;
    private Coarseness coarseness;
    private byte[] constantData;
    private int constantSize;
    private long[] globalOffset;
    private int globalSize;
    private long[] globalWork;
    private int localSize;
    private long[] localWork;
    private int privateSize;
    private final ScheduleMetaData scheduleMetaData;
    protected Access[] argumentsAccess;
    protected DomainTree domain;
    protected final Map<TornadoAcceleratorDevice, BitSet> profiles;
    private boolean schedule;
    private boolean localWorkDefined;
    private boolean globalWorkDefined;
    private boolean canAssumeExact;

    public TaskMetaData(ScheduleMetaData scheduleMetaData, String id, int numParameters) {
        super(scheduleMetaData.getId() + "." + id);
        this.scheduleMetaData = scheduleMetaData;
        this.globalSize = 0;
        this.constantSize = 0;
        this.localSize = 0;
        this.privateSize = 0;
        this.constantData = null;
        profiles = new HashMap<>();
        argumentsAccess = new Access[numParameters];
        Arrays.fill(argumentsAccess, Access.NONE);
        this.idTask = scheduleMetaData.getId() + "." + id;

        localWorkDefined = getProperty(getId() + ".local.dims") != null;
        if (localWorkDefined) {
            final String[] values = getProperty(getId() + ".local.dims").split(",");
            localWork = new long[values.length];
            for (int i = 0; i < values.length; i++) {
                localWork[i] = Long.parseLong(values[i]);
            }
        }

        globalWorkDefined = getProperty(getId() + ".global.dims") != null;
        if (globalWorkDefined) {
            final String[] values = getProperty(getId() + ".global.dims").split(",");
            globalWork = new long[values.length];
            for (int i = 0; i < values.length; i++) {
                globalWork[i] = Long.parseLong(values[i]);
            }
        }

        this.schedule = !(globalWorkDefined && localWorkDefined);
        this.canAssumeExact = Boolean.parseBoolean(getDefault("coarsener.exact", getId(), "False"));
    }

    public static TaskMetaData create(ScheduleMetaData scheduleMeta, String id, Method method, boolean readMetaData) {
        final TaskMetaData meta = new TaskMetaData(scheduleMeta, id, Modifier.isStatic(method.getModifiers()) ? method.getParameterCount() : method.getParameterCount() + 1);
        return meta;
    }

    private static String formatArray(final long[] array) {
        final StringBuilder sb = new StringBuilder();

        sb.append("[");
        for (final long value : array) {
            sb.append(" ").append(value);
        }
        sb.append(" ]");

        return sb.toString();
    }

    private static String getProperty(String key) {
        return System.getProperty(key);
    }

    public boolean canAssumeExact() {
        return canAssumeExact;
    }

    public boolean isLocalWorkDefined() {
        return localWorkDefined;
    }

    public boolean isGlobalWorkDefined() {
        return globalWorkDefined;
    }

    @Override
    public void setGlobalWork(long[] values) {
        if (globalWorkDefined) {
            return;
        }

        for (int i = 0; i < values.length; i++) {
            globalWork[i] = values[i];
        }
        globalWorkDefined = true;
        schedule = !(globalWorkDefined && localWorkDefined);
    }

    @Override
    public void setLocalWork(long[] values) {
        for (int i = 0; i < values.length; i++) {
            localWork[i] = values[i];
        }
        localWorkDefined = true;
        schedule = !(globalWorkDefined && localWorkDefined);
    }

    public void setLocalWorkToNull() {
        localWork = null;
    }

    public void setSchedule(boolean value) {
        schedule = value;
    }

    public boolean shouldSchedule() {
        return schedule;
    }

    public void addProfile(int id) {
        final TornadoAcceleratorDevice device = getDevice();
        BitSet events = null;
        if (!profiles.containsKey(device)) {
            events = new BitSet(EVENT_WINDOW);
            profiles.put(device, events);
        }
        events = profiles.get(device);
        events.set(id);
    }

    public void allocConstant(int size) {
        this.constantSize = size;
        constantData = new byte[size];
    }

    public void allocGlobal(int size) {
        this.globalSize = size;
    }

    public void allocLocal(int size) {
        this.localSize = size;
    }

    public void allocPrivate(int size) {
        this.privateSize = size;
    }

    @Override
    public boolean enableAutoParallelisation() {
        return super.enableAutoParallelisation() || scheduleMetaData.enableAutoParallelisation();
    }

    @Override
    public boolean enableExceptions() {
        return super.enableExceptions() || scheduleMetaData.enableExceptions();
    }

    @Override
    public boolean enableMemChecks() {
        return super.enableMemChecks() || scheduleMetaData.enableMemChecks();
    }

    @Override
    public boolean enableOooExecution() {
        return super.enableOooExecution() || scheduleMetaData.enableOooExecution();
    }

    @Override
    public boolean enableOpenclBifs() {
        return super.enableOpenclBifs() || scheduleMetaData.enableOpenclBifs();
    }

    @Override
    public boolean enableParallelization() {
        return scheduleMetaData.isEnableParallelizationDefined() && !isEnableParallelizationDefined() ? scheduleMetaData.enableParallelization() : super.enableParallelization();
    }

    @Override
    public boolean enableProfiling() {
        return super.enableProfiling() || scheduleMetaData.enableProfiling();
    }

    @Override
    public boolean enableVectors() {
        return super.enableVectors() || scheduleMetaData.enableVectors();
    }

    @Override
    public String getCpuConfig() {
        if (super.isCpuConfigDefined()) {
            return super.getCpuConfig();
        } else if (!super.isCpuConfigDefined() && scheduleMetaData.isCpuConfigDefined()) {
            return scheduleMetaData.getCpuConfig();
        } else {
            return "";
        }
    }

    public Access[] getArgumentsAccess() {
        return argumentsAccess;
    }

    public Coarseness getCoarseness() {
        return coarseness;
    }

    public int getCoarseness(int index) {
        return coarseness.getCoarseness(index);
    }

    public byte[] getConstantData() {
        return constantData;
    }

    public int getConstantSize() {
        return constantSize;
    }

    @Override
    public TornadoAcceleratorDevice getDevice() {
        if (scheduleMetaData.isDeviceManuallySet()) {
            return scheduleMetaData.getDevice();
        } else if (scheduleMetaData.isDeviceDefined() && !isDeviceDefined()) {
            return scheduleMetaData.getDevice();
        }
        return super.getDevice();
    }

    public int getDims() {
        return domain.getDepth();
    }

    public DomainTree getDomain() {
        return domain;
    }

    public void setDomain(final DomainTree value) {

        domain = value;
        coarseness = new Coarseness(domain.getDepth());

        final String config = getProperty(getId() + ".coarseness");
        if (config != null && !config.isEmpty()) {
            coarseness.applyConfig(config);
        }

        final int dims = domain.getDepth();
        globalOffset = new long[dims];
        if (!globalWorkDefined) {
            globalWork = new long[dims];
        }
        if (localWorkDefined) {
            guarantee(localWork.length == dims, "task %s has local work dims specified of wrong length", getId());
        } else {
            localWork = new long[dims];
        }
    }

    public long[] getGlobalOffset() {
        return globalOffset;
    }

    public int getGlobalSize() {
        return globalSize;
    }

    @Override
    public long[] getGlobalWork() {
        return globalWork;
    }

    public int getLocalSize() {
        return localSize;
    }

    @Override
    public long[] getLocalWork() {
        return localWork;
    }

    @Override
    public String getCompilerFlags() {
        return isOpenclCompilerFlagsDefined() ? super.getCompilerFlags() : scheduleMetaData.getCompilerFlags();
    }

    @Override
    public int getOpenCLGpuBlock2DX() {
        return isOpenclGpuBlock2DXDefined() ? super.getOpenCLGpuBlock2DX() : scheduleMetaData.getOpenCLGpuBlock2DX();
    }

    @Override
    public int getOpenCLGpuBlock2DY() {
        return isOpenclGpuBlock2DXDefined() ? super.getOpenCLGpuBlock2DY() : scheduleMetaData.getOpenCLGpuBlock2DY();
    }

    @Override
    public int getOpenCLGpuBlockX() {
        return isOpenclGpuBlockXDefined() ? super.getOpenCLGpuBlockX() : scheduleMetaData.getOpenCLGpuBlockX();
    }

    public int getPrivateSize() {
        return privateSize;
    }

    public List<TornadoEvents> getProfiles() {
        final List<TornadoEvents> result = new ArrayList<>(profiles.keySet().size());
        for (TornadoAcceleratorDevice device : profiles.keySet()) {
            result.add(new EventSet(device, profiles.get(device)));
        }
        return result;
    }

    public String getScheduleId() {
        return scheduleMetaData.getId();
    }

    public boolean hasDomain() {
        return domain != null;
    }

    @Override
    public boolean isDebug() {
        return super.isDebug() || scheduleMetaData.isDebug();
    }

    public boolean isParallel() {
        return enableParallelization() && hasDomain() && domain.getDepth() > 0;
    }

    public void printThreadDims() {
        System.out.printf("task info: %s\n", idTask);
        System.out.printf("\tplatform          : %s\n", getDevice().getPlatformName());
        System.out.printf("\tdevice            : %s\n", getDevice().getDescription());
        System.out.printf("\tdims              : %d\n", domain.getDepth());
        System.out.printf("\tglobal work offset: %s\n", formatArray(globalOffset));
        System.out.printf("\tglobal work size  : %s\n", formatArray(globalWork));
        System.out.printf("\tlocal  work size  : %s\n", localWork == null ? "null" : formatArray(localWork));
    }

    public void setCoarseness(int index, int value) {
        coarseness.setCoarseness(index, value);
    }

    @Override
    public boolean shouldDebugKernelArgs() {
        return super.shouldDebugKernelArgs() || scheduleMetaData.shouldDebugKernelArgs();
    }

    @Override
    public boolean shouldDumpProfiles() {
        return super.shouldDumpProfiles() || scheduleMetaData.shouldDumpProfiles();
    }

    @Override
    public boolean shouldDumpEvents() {
        return super.shouldDumpEvents() || scheduleMetaData.shouldDumpEvents();
    }

    @Override
    public boolean shouldPrintCompileTimes() {
        return super.shouldPrintCompileTimes() || scheduleMetaData.shouldPrintCompileTimes();
    }

    @Override
    public boolean shouldPrintKernelExecutionTime() {
        return super.shouldPrintKernelExecutionTime() || scheduleMetaData.shouldPrintKernelExecutionTime();
    }

    @Override
    public boolean shouldUseOpenclBlockingApiCalls() {
        return super.shouldUseOpenclBlockingApiCalls() || scheduleMetaData.shouldUseOpenclBlockingApiCalls();
    }

    @Override
    public boolean shouldUseOpenclRelativeAddresses() {
        return super.shouldUseOpenclRelativeAddresses() || scheduleMetaData.shouldUseOpenclRelativeAddresses();
    }

    @Override
    public boolean shouldUseOpenclScheduling() {
        return super.shouldUseOpenclScheduling() || scheduleMetaData.shouldUseOpenclScheduling();
    }

    @Override
    public boolean shouldUseOpenclWaitActive() {
        return super.shouldUseOpenclWaitActive() || scheduleMetaData.shouldUseOpenclWaitActive();
    }

    @Override
    public boolean shouldUseVmWaitEvent() {
        return super.shouldUseVmWaitEvent() || scheduleMetaData.shouldUseVmWaitEvent();
    }

    @Override
    public boolean enableThreadCoarsener() {
        return super.enableThreadCoarsener() || scheduleMetaData.enableThreadCoarsener();
    }

    @Override
    public boolean shouldCoarsenWithCpuConfig() {
        return super.shouldCoarsenWithCpuConfig() || scheduleMetaData.shouldCoarsenWithCpuConfig();
    }

    @Override
    public boolean isCpuConfigDefined() {
        return super.isCpuConfigDefined() || scheduleMetaData.isCpuConfigDefined();
    }

    @Override
    public String toString() {
        return String.format("task meta data: domain=%s, global dims=%s\n", domain, (getGlobalWork() == null) ? "null" : formatArray(getGlobalWork()));
    }

}
