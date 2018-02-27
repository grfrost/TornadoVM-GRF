/*
 * This file is part of Tornado: A heterogeneous programming framework: 
 * https://github.com/beehive-lab/tornado
 *
 * Copyright (c) 2013-2018 APT Group, School of Computer Science, 
 * The University of Manchester
 *
 * This work is partially supported by EPSRC grants:
 * Anyscale EP/L000725/1 and PAMELA EP/K008730/1.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Authors: James Clarkson
 *
 */
package tornado.drivers.opencl.mm;

import static uk.ac.manchester.tornado.common.Tornado.DEBUG;
import static uk.ac.manchester.tornado.common.Tornado.debug;
import static uk.ac.manchester.tornado.common.Tornado.trace;
import static uk.ac.manchester.tornado.common.Tornado.warn;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import uk.ac.manchester.tornado.common.ObjectBuffer;
import uk.ac.manchester.tornado.common.RuntimeUtilities;
import uk.ac.manchester.tornado.common.exceptions.TornadoOutOfMemoryException;

public class FieldBuffer {

    private final Field field;

    private final ObjectBuffer objectBuffer;

    public FieldBuffer(final Field field, final ObjectBuffer objectBuffer) {
        this.objectBuffer = objectBuffer;
        this.field = field;
    }

    public boolean isFinal() {
        return Modifier.isFinal(field.getModifiers());
    }

    public void allocate(final Object ref) throws TornadoOutOfMemoryException {
        objectBuffer.allocate(getFieldValue(ref));
    }

    public int enqueueRead(final Object ref, final int[] events, boolean useDeps) {
        if (DEBUG) {
            trace("fieldBuffer: enqueueRead* - field=%s, parent=0x%x, child=0x%x", field, ref.hashCode(), getFieldValue(ref).hashCode());
        }
        return (useDeps) ? objectBuffer.enqueueRead(getFieldValue(ref), (useDeps) ? events : null, useDeps) : -1;
    }

    public int enqueueWrite(final Object ref, final int[] events, boolean useDeps) {
        if (DEBUG) {
            trace("fieldBuffer: enqueueWrite* - field=%s, parent=0x%x, child=0x%x", field, ref.hashCode(), getFieldValue(ref).hashCode());
        }
        return (useDeps) ? objectBuffer.enqueueWrite(getFieldValue(ref), (useDeps) ? events : null, useDeps) : -1;
    }

    public int getAlignment() {
        return objectBuffer.getAlignment();
    }

    public long getBufferOffset() {
        return objectBuffer.getBufferOffset();
    }

    private Object getFieldValue(final Object container) {
        Object value = null;
        try {
            value = field.get(container);
        } catch (IllegalArgumentException | IllegalAccessException e) {
            warn("Illegal access to field: name=%s, object=0x%x", field.getName(), container.hashCode());
        }
        return value;
    }

    public boolean onDevice() {
        return objectBuffer.isValid();
    }

    public void read(final Object ref) {
        read(ref, null, false);
    }

    public void read(final Object ref, int[] events, boolean useDeps) {
        if (DEBUG) {
            debug("fieldBuffer: read - field=%s, parent=0x%x, child=0x%x", field, ref.hashCode(), getFieldValue(ref).hashCode());
        }
        objectBuffer.read(getFieldValue(ref), events, useDeps);
    }

    public long toAbsoluteAddress() {
        return objectBuffer.toAbsoluteAddress();
    }

    public long toBuffer() {
        return objectBuffer.toBuffer();
    }

    public long toRelativeAddress() {
        return objectBuffer.toRelativeAddress();
    }

    public boolean needsWrite() {
        return !onDevice() || !RuntimeUtilities.isPrimitive(field.getType());
    }

    public void write(final Object ref) {
        if (DEBUG) {
            trace("fieldBuffer: write - field=%s, parent=0x%x, child=0x%x", field, ref.hashCode(), getFieldValue(ref).hashCode());
        }
        objectBuffer.write(getFieldValue(ref));
    }

    public String getFieldName() {
        return field.getName();
    }

}
