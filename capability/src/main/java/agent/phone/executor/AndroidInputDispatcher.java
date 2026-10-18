package agent.phone.executor;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;

/** UID-targeted touch injection for the owned target display; no untargeted fallback. */
final class AndroidInputDispatcher implements TargetLifecycle.UserInput {
    @Override public boolean tap(int displayId, int targetUid, int x, int y) throws Exception {
        if (displayId < 0 || targetUid <= 0 || x < 0 || y < 0) {
            throw new IllegalArgumentException("Invalid tap target");
        }
        Class<?> managerType = Class.forName("android.hardware.input.InputManagerGlobal");
        Object manager = managerType.getMethod("getInstance").invoke(null);
        Method inject = managerType.getMethod("injectInputEvent", InputEvent.class, int.class, int.class);
        int waitForResult = Class.forName("android.os.InputEventInjectionSync")
                .getField("WAIT_FOR_RESULT").getInt(null);
        Method setDisplay = MotionEvent.class.getMethod("setDisplayId", int.class);
        long down = SystemClock.uptimeMillis();
        for (int index = 0; index < 2; index++) {
            MotionEvent event = MotionEvent.obtain(down,
                    index == 0 ? down : SystemClock.uptimeMillis(),
                    index == 0 ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP, x, y, 0);
            try {
                event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                setDisplay.invoke(event, displayId);
                Object accepted = inject.invoke(manager, event, waitForResult, targetUid);
                if (!(accepted instanceof Boolean) || !((Boolean) accepted)) return false;
            } finally {
                event.recycle();
            }
            if (index == 0) SystemClock.sleep(35);
        }
        return true;
    }

    @Override public boolean swipe(int displayId,int targetUid,int startX,int startY,int endX,int endY,int durationMs) throws Exception {
        if (displayId < 0 || targetUid <= 0 || startX < 0 || startY < 0 || endX < 0 || endY < 0
                || durationMs < 100 || durationMs > 2000) {
            throw new IllegalArgumentException("Invalid swipe target");
        }
        Class<?> managerType = Class.forName("android.hardware.input.InputManagerGlobal");
        Object manager = managerType.getMethod("getInstance").invoke(null);
        Method inject = managerType.getMethod("injectInputEvent", InputEvent.class, int.class, int.class);
        int waitForResult = Class.forName("android.os.InputEventInjectionSync")
                .getField("WAIT_FOR_RESULT").getInt(null);
        Method setDisplay = MotionEvent.class.getMethod("setDisplayId", int.class);
        long down = SystemClock.uptimeMillis();
        int steps = 10;
        for (int index = 0; index <= steps; index++) {
            float fraction = index / (float) steps;
            int x = Math.round(startX + (endX - startX) * fraction);
            int y = Math.round(startY + (endY - startY) * fraction);
            int action = index == 0 ? MotionEvent.ACTION_DOWN
                    : index == steps ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
            long eventTime = down + (long) (durationMs * fraction);
            MotionEvent event = MotionEvent.obtain(down,eventTime,action,x,y,0);
            try {
                event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                setDisplay.invoke(event,displayId);
                Object accepted = inject.invoke(manager,event,waitForResult,targetUid);
                if (!(accepted instanceof Boolean) || !((Boolean) accepted)) return false;
            } finally { event.recycle(); }
            long remaining = eventTime - SystemClock.uptimeMillis();
            if (remaining > 0) SystemClock.sleep(remaining);
        }
        return true;
    }

    boolean key(int displayId,int targetUid,int keyCode) throws Exception {
        if (displayId < 0 || targetUid <= 0 || keyCode < 0) throw new IllegalArgumentException("Invalid key target");
        Class<?> managerType = Class.forName("android.hardware.input.InputManagerGlobal");
        Object manager = managerType.getMethod("getInstance").invoke(null);
        Method inject = managerType.getMethod("injectInputEvent", InputEvent.class, int.class, int.class);
        int waitForResult = Class.forName("android.os.InputEventInjectionSync")
                .getField("WAIT_FOR_RESULT").getInt(null);
        Method setDisplay = KeyEvent.class.getMethod("setDisplayId", int.class);
        long down = SystemClock.uptimeMillis();
        for (int index = 0; index < 2; index++) {
            KeyEvent event = new KeyEvent(down,index == 0 ? down : SystemClock.uptimeMillis(),
                    index == 0 ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP,keyCode,0);
            event.setSource(InputDevice.SOURCE_KEYBOARD);
            setDisplay.invoke(event,displayId);
            Object accepted = inject.invoke(manager,event,waitForResult,targetUid);
            if (!(accepted instanceof Boolean) || !((Boolean) accepted)) return false;
            if (index == 0) SystemClock.sleep(35);
        }
        return true;
    }
}
