package org.gradle.trace;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.MemoryUsage;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.management.ListenerNotFoundException;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;

import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.OperatingSystemMXBean;
import org.gradle.BuildAdapter;
import org.gradle.BuildResult;
import org.gradle.api.Plugin;
import org.gradle.api.execution.internal.TaskOperationDescriptor;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.TaskInternal;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.tasks.ParallelizableTask;
import org.gradle.initialization.BuildRequestMetaData;
import org.gradle.initialization.DefaultGradleLauncherFactory;
import org.gradle.initialization.GradleLauncherFactory;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.event.ListenerManager;
import org.gradle.internal.progress.BuildOperationInternal;
import org.gradle.internal.progress.InternalBuildListener;
import org.gradle.internal.progress.OperationResult;
import org.gradle.internal.progress.OperationStartEvent;
import org.gradle.internal.service.ServiceRegistry;

import java.lang.management.ManagementFactory;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class GradleTracingPlugin implements Plugin<Gradle> {
    private static final String CATEGORY_PHASE = "BUILD_PHASE";
    private static final String CATEGORY_OPERATION = "BUILD_OPERATION";
    private static final String PHASE_BUILD = "build duration";
    public static final String GARBAGE_COLLECTION = "GARBAGE_COLLECTION";
    private final BuildRequestMetaData buildRequestMetaData;
    private final Map<String, TraceEvent> events = new LinkedHashMap<>();
    private final OperatingSystemMXBean operatingSystemMXBean;
    private final List<GarbageCollectorMXBean> garbageCollectorMXBeans;
    private int sysPollCount = 0;
    private final long jvmStartTime;
    private Map<GarbageCollectorMXBean, NotificationListener> gcNotificationListeners = new HashMap<>();
    private ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
    private final long maxHeap;

    @Inject
    public GradleTracingPlugin(BuildRequestMetaData buildRequestMetaData) {
        this.buildRequestMetaData = buildRequestMetaData;
        this.operatingSystemMXBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        this.garbageCollectorMXBeans = ManagementFactory.getGarbageCollectorMXBeans();
        this.jvmStartTime = ManagementFactory.getRuntimeMXBean().getStartTime();
        this.maxHeap = Runtime.getRuntime().maxMemory();
    }

    private void start(String name, String category, long timestampNanos) {
        start(name, category, timestampNanos, null);
    }

    private void start(String name, String category, long timestampNanos, String colorName) {
        events.put(name, new DurationEvent(name, category, timestampNanos, new HashMap<>(), colorName));
    }

    private void finish(String name, long timestampNanos, Map<String, String> info) {
        DurationEvent event = (DurationEvent) events.get(name);
        if (event != null) {
            event.finished(timestampNanos);
            event.getInfo().putAll(info);
        }
    }

    private void count(String name, String metric, Map<String, Double> info) {
        count(name, metric, info, null);
    }

    private void count(String name, String metric, Map<String, Double> info, String colorName) {
        events.put(name, new CountEvent(metric, info, colorName));
    }

    @Override
    public void apply(Gradle gradle) {
        ListenerManager globalListenerManager = getGlobalListenerManager(gradle);

        scheduledExecutorService.scheduleAtFixedRate(() -> {
            HashMap<String, Double> cpuStats = new HashMap<>();
            double pcpu = operatingSystemMXBean.getProcessCpuLoad()*100;
            double scpu = operatingSystemMXBean.getSystemCpuLoad()*100;

            if (!Double.isNaN(pcpu) && !Double.isNaN(scpu)) {
                cpuStats.put("process_cpu_used", pcpu);
                cpuStats.put("non_process_cpu_used", scpu - pcpu);

                count("cpu" + sysPollCount, "cpu", cpuStats);
            }

            sysPollCount++;
        }, 0, 500, TimeUnit.MILLISECONDS);

        for (GarbageCollectorMXBean garbageCollectorMXBean : garbageCollectorMXBeans) {
            NotificationEmitter emitter = (NotificationEmitter) garbageCollectorMXBean;
            NotificationListener gcNotificationListener = (notification, handback) -> {
                if (notification.getType().equals(GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION)) {
                    GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
                    //get all the info and pretty print it
                    long duration = info.getGcInfo().getDuration();
                    String gctype = info.getGcAction();
                    Map<String, String> args = new HashMap<>();
                    args.put("type", gctype);

                    String colorName = "good";
                    if (gctype.equals("end of major GC")) {
                        colorName = "terrible";
                    }

                    start("GC" + info.getGcInfo().getId(), GARBAGE_COLLECTION, toNanoTime(jvmStartTime + info.getGcInfo().getStartTime()), colorName);
                    finish("GC" + info.getGcInfo().getId(), toNanoTime(jvmStartTime + info.getGcInfo().getEndTime()), args);
                    Map<String, MemoryUsage> pools = info.getGcInfo().getMemoryUsageAfterGc();
                    long unallocatedHeap = maxHeap;
                    HashMap<String, Double> gcInfo = new LinkedHashMap<>();
                    for (String pool : pools.keySet()) {
                        MemoryUsage usage = pools.get(pool);
                        gcInfo.put(pool, (double) usage.getUsed());
                        unallocatedHeap -= usage.getUsed();
                    }
                    gcInfo.put("unallocated", (double) unallocatedHeap);

                    count("heap" + info.getGcInfo().getId(), "heap", gcInfo);
                }
            };
            emitter.addNotificationListener(gcNotificationListener, null, null);
            gcNotificationListeners.put(garbageCollectorMXBean, gcNotificationListener);
        }

        globalListenerManager.addListener( new InternalBuildListener() {
                @Override
                public void started(BuildOperationInternal operation, OperationStartEvent startEvent) {
                    start(getName(operation), CATEGORY_OPERATION, toNanoTime(startEvent.getStartTime()));
                }

                @Override
                public void finished(BuildOperationInternal operation, OperationResult result) {
                    Map<String, String> info = new HashMap<>();
                    if (operation.getOperationDescriptor() instanceof TaskOperationDescriptor) {
                        TaskOperationDescriptor taskDescriptor = (TaskOperationDescriptor) operation.getOperationDescriptor();
                        withTaskInfo(info, taskDescriptor);
                    }
                    finish(getName(operation), toNanoTime(result.getEndTime()), info);
                }

                private String getName(BuildOperationInternal operation) {
                    if (operation.getOperationDescriptor() instanceof  TaskOperationDescriptor) {
                        return ((TaskOperationDescriptor)operation.getOperationDescriptor()).getTask().getPath();
                    }
                    return operation.getDisplayName();
                }

                private void withTaskInfo(Map<String, String> info, TaskOperationDescriptor taskDescriptor) {
                    TaskInternal task = taskDescriptor.getTask();
                    info.put("type", task.getClass().getSimpleName().replace("_Decorated", ""));
                    info.put("enabled", String.valueOf(task.getEnabled()));
                    info.put("cacheable", String.valueOf(task.getState().isCacheable()));
                    info.put("parallelizeable", String.valueOf(task.getClass().isAnnotationPresent(ParallelizableTask.class) && !task.isHasCustomActions()));
                    info.put("outcome", task.getState().getOutcome().name());
                }
            });
        gradle.getGradle().addListener(new JsonAdapter(gradle));
    }

    private class JsonAdapter extends BuildAdapter {
        private final Gradle gradle;

        private JsonAdapter(Gradle gradle) {
            this.gradle = gradle;
        }

        @Override
        public void buildFinished(BuildResult result) {
            scheduledExecutorService.shutdown();

            for (GarbageCollectorMXBean garbageCollectorMXBean : gcNotificationListeners.keySet()) {
                NotificationEmitter emitter = (NotificationEmitter) garbageCollectorMXBean;
                try {
                    emitter.removeNotificationListener(gcNotificationListeners.get(garbageCollectorMXBean));
                } catch (ListenerNotFoundException e) {
                }
            }

            start(PHASE_BUILD, CATEGORY_PHASE, toNanoTime(buildRequestMetaData.getBuildTimeClock().getStartTime()));
            finish(PHASE_BUILD, System.nanoTime(), new HashMap<>());

            File traceFile = getTraceFile();

            copyResourceToFile("/trace-header.html", traceFile, false);
            writeEvents(traceFile);
            copyResourceToFile("/trace-footer.html", traceFile, true);

            result.getGradle().getRootProject().getLogger().lifecycle("Trace written to file://" + traceFile.getAbsolutePath());
        }

        private void copyResourceToFile(String resourcePath, File traceFile, boolean append) {
            try(OutputStream out = new FileOutputStream(traceFile, append);
                InputStream in = getClass().getResourceAsStream(resourcePath)) {
                byte[] buffer = new byte[1024];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                }
                } catch (IOException e) {
                throw UncheckedException.throwAsUncheckedException(e);
            }
        }

        private File getTraceFile() {
            File traceFile = (File) gradle.getRootProject().findProperty("chromeTraceFile");
            if (traceFile == null) {
                traceFile = defaultTraceFile();
            }
            traceFile.getParentFile().mkdirs();
            return traceFile;
        }

        private File defaultTraceFile() {
            File traceFile;
            File buildDir = gradle.getRootProject().getBuildDir();
            traceFile = new File(buildDir, "trace/task-trace.html");
            return traceFile;
        }
    }

    private void writeEvents(File traceFile) {
        PrintWriter writer = getPrintWriter(traceFile);
        writer.println("{\n" +
                "  \"traceEvents\": [\n");

        Iterator<TraceEvent> itr = events.values().iterator();
        while (itr.hasNext()) {
            writer.print(itr.next().toString());
            writer.println(itr.hasNext() ? "," : "");
        }

        writer.println("],\n" +
                "  \"displayTimeUnit\": \"ns\",\n" +
                "  \"systemTraceEvents\": \"SystemTraceData\",\n" +
                "  \"otherData\": {\n" +
                "    \"version\": \"My Application v1.0\"\n" +
                "  }\n" +
                "}\n");
    }

    private PrintWriter getPrintWriter(File jsonFile) {
        try {
            return new PrintWriter(new FileWriter(jsonFile, true), true);
        } catch (IOException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    private long toNanoTime(long timeInMillis) {
        long elapsedMillis = System.currentTimeMillis() - timeInMillis;
        long elapsedNanos = TimeUnit.MILLISECONDS.toNanos(elapsedMillis);
        return System.nanoTime() - elapsedNanos;
    }


    private ListenerManager getGlobalListenerManager( final Gradle gradle ) {
        try {
            GradleInternal gradleInternal = (GradleInternal) gradle;
            ServiceRegistry services = gradleInternal.getServices();
            GradleLauncherFactory gradleLauncherFactory = services.get(GradleLauncherFactory.class);
            Field field = DefaultGradleLauncherFactory.class.getDeclaredField("globalListenerManager");
            field.setAccessible(true);
            return (ListenerManager) field.get(gradleLauncherFactory);
        } catch(Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
