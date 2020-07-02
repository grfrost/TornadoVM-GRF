package uk.ac.manchester.tornado.drivers.cuda;

import uk.ac.manchester.tornado.drivers.cuda.graal.PTXInstalledCode;
import uk.ac.manchester.tornado.drivers.cuda.graal.compiler.PTXCompilationResult;
import uk.ac.manchester.tornado.runtime.tasks.meta.TaskMetaData;

import java.util.concurrent.ConcurrentHashMap;

import static java.util.jar.Pack200.Unpacker.FALSE;
import static uk.ac.manchester.tornado.api.exceptions.TornadoInternalError.shouldNotReachHere;
import static uk.ac.manchester.tornado.runtime.common.Tornado.getProperty;

public class CUDACodeCache {

    private static final boolean PRINT_SOURCE = Boolean.parseBoolean(getProperty("tornado.opencl.source.print", FALSE));
    private final CUDADeviceContext deviceContext;
    private final ConcurrentHashMap<String, PTXInstalledCode> cache;

    public CUDACodeCache(CUDADeviceContext deviceContext) {
        this.deviceContext = deviceContext;
        cache = new ConcurrentHashMap<>();
    }

    public PTXInstalledCode installSource(String name, byte[] targetCode, TaskMetaData taskMeta, String resolvedMethodName) {
        String cacheKey = name;

        if (!cache.containsKey(cacheKey)) {
            if (PRINT_SOURCE) {
                String source = new String(targetCode);
                System.out.println(source);
            }

            CUDAModule module = new CUDAModule(resolvedMethodName, targetCode, name, taskMeta);

            if (module.getIsPTXJITSuccess()) {
                PTXInstalledCode code = new PTXInstalledCode(name, module, deviceContext);
                cache.put(cacheKey, code);
                return code;
            }
            else {
                shouldNotReachHere("PTX JIT compilation failed!");
            }
        }

        return cache.get(cacheKey);
    }

    public PTXInstalledCode getCachedCode(String name) {
        return cache.get(name);
    }

    public boolean isCached(String name) {
        return cache.containsKey(name);
    }

    public void reset() {
        cache.clear();
    }
}
