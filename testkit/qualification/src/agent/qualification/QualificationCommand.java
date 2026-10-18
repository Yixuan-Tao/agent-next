package agent.qualification;

import android.os.Looper;

import java.lang.reflect.InvocationTargetException;

import org.json.JSONObject;

/** Minimal app_process bootstrap shared by the frame and input shell diagnostics. */
final class QualificationCommand {
    interface Operation {
        JSONObject run() throws Exception;
    }

    private QualificationCommand() {
    }

    static void start(final Operation operation) {
        try {
            Looper.prepare();
            // API 36 accessibility queries need the app_process main-looper test hook.
            Looper.class.getMethod("setMainLooperForTest", Looper.class).invoke(null, Looper.myLooper());
            new Thread(new Runnable() {
                @Override
                public void run() {
                    int status = 0;
                    try {
                        JSONObject result = operation.run();
                        System.out.println(result.toString());
                    } catch (Throwable failure) {
                        reportFailure(failure);
                        status = 1;
                    }
                    System.exit(status);
                }
            }, "QualificationCommand").start();
            Looper.loop();
        } catch (Throwable failure) {
            reportFailure(failure);
            System.exit(1);
        }
    }

    static void reportFailure(Throwable failure) {
        while (failure instanceof InvocationTargetException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        System.err.println("error=" + failure.getClass().getSimpleName() + ": " + failure.getMessage());
    }
}
