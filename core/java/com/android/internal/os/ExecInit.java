package com.android.internal.os;

import android.os.Process;
import android.os.Trace;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Slog;
import android.util.TimingsTraceLog;
import dalvik.system.VMRuntime;

/**
 * Startup class for the process.
 * @hide
 */
public class ExecInit {
    /**
     * Class not instantiable.
     */
    private ExecInit() {
    }

    /**
     * The main function called when starting a runtime application.
     *
     * The first argument is the target SDK version for the app.
     *
     * The remaining arguments are passed to the runtime.
     *
     * @param args The command-line arguments.
     */
    public static void main(String[] args) {
        // Parse our mandatory argument.
        int targetSdkVersion = Integer.parseInt(args[0], 10);

        // Parse the runtime_flags.
        int runtimeFlags = Integer.parseInt(args[1], 10);

        // Mimic system Zygote preloading.
        ZygoteInit.preload(new TimingsTraceLog("ExecInitTiming",
                Trace.TRACE_TAG_DALVIK), false);

        // Launch the application.
        String[] runtimeArgs = new String[args.length - 2];
        System.arraycopy(args, 2, runtimeArgs, 0, runtimeArgs.length);
        Runnable r = execInit(targetSdkVersion, runtimeArgs);

        Zygote.nativeHandleRuntimeFlags(runtimeFlags);

        r.run();
    }

    /**
     * Executes a runtime application with exec-based spawning.
     * This method never returns.
     *
     * @param niceName The nice name for the application, or null if none.
     * @param targetSdkVersion The target SDK version for the app.
     * @param args Arguments for {@link RuntimeInit#main}.
     */
    public static void execApplication(String niceName, int targetSdkVersion,
            String instructionSet, int runtimeFlags, String[] args) {
        int niceArgs = niceName == null ? 0 : 1;
        int baseArgs = 6 + niceArgs;
        String[] argv = new String[baseArgs + args.length];
        if (VMRuntime.is64BitInstructionSet(instructionSet)) {
            argv[0] = "/system/bin/app_process64";
        } else {
            argv[0] = "/system/bin/app_process32";
        }
        argv[1] = "/system/bin";
        argv[2] = "--application";
        if (niceName != null) {
            argv[3] = "--nice-name=" + niceName;
        }
        argv[3 + niceArgs] = "com.android.internal.os.ExecInit";
        argv[4 + niceArgs] = Integer.toString(targetSdkVersion);
        argv[5 + niceArgs] = Integer.toString(runtimeFlags);
        System.arraycopy(args, 0, argv, baseArgs, args.length);

        WrapperInit.preserveCapabilities();
        try {
            if ((runtimeFlags & Zygote.DISABLE_HARDENED_MALLOC) != 0) {
                // checked by bionic during early init
                Os.setenv("DISABLE_HARDENED_MALLOC", "1", true);
            }

            if ((runtimeFlags & Zygote.ENABLE_COMPAT_VA_39_BIT) != 0) {
                final int FLAG_COMPAT_VA_39_BIT = 1 << 30;

                int errno = Zygote.execveatWrapper(-1, argv[0], argv, FLAG_COMPAT_VA_39_BIT);

                if (errno == OsConstants.EINVAL) {
                    // kernel doesn't support FLAG_COMPAT_VA_39_BIT, or a different error that will
                    // be thrown by execv() anyway
                    Os.execv(argv[0], argv);
                } else {
                    throw new ErrnoException("execveat", errno);
                }
            } else {
                Os.execv(argv[0], argv);
            }
        } catch (ErrnoException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean isExecSpawned;

    /**
     * The main function called when an application is started with exec-based spawning.
     *
     * When the app starts, the runtime starts {@link RuntimeInit#main}
     * which calls {@link main} which then calls this method.
     * So we don't need to call commonInit() here.
     *
     * @param targetSdkVersion target SDK version
     * @param argv arg strings
     */
    private static Runnable execInit(int targetSdkVersion, String[] argv) {
        if (RuntimeInit.DEBUG) {
            Slog.d(RuntimeInit.TAG, "RuntimeInit: Starting application from exec");
        }

        isExecSpawned = true;

        // Check whether the first argument is a "-cp" in argv, and assume the next argument is the
        // classpath. If found, create a PathClassLoader and use it for applicationInit.
        ClassLoader classLoader = null;
        if (argv != null && argv.length > 2 && argv[0].equals("-cp")) {
            classLoader = ZygoteInit.createPathClassLoader(argv[1], targetSdkVersion);

            // Install this classloader as the context classloader, too.
            Thread.currentThread().setContextClassLoader(classLoader);

            // Remove the classpath from the arguments.
            String removedArgs[] = new String[argv.length - 2];
            System.arraycopy(argv, 2, removedArgs, 0, argv.length - 2);
            argv = removedArgs;
        }

        // Perform the same initialization that would happen after the Zygote forks.
        Zygote.nativePreApplicationInit();
        if (Process.isIsolated()) {
            System.gc();
        }
        return RuntimeInit.applicationInit(targetSdkVersion, /*disabledCompatChanges*/ null, argv, classLoader);
    }
}
