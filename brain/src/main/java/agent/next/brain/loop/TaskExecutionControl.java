package agent.next.brain.loop;

import java.util.HashSet;
import java.util.Set;

/** Independent pause owners prevent closing a preview from resuming a task paused by the user. */
public final class TaskExecutionControl implements LoopControl {
    private boolean stopped;
    private final Set<String> pauseReasons = new HashSet<>();

    @Override
    public synchronized void checkpoint() throws LoopStoppedException {
        while (!pauseReasons.isEmpty() && !stopped) {
            try { wait(); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new LoopStoppedException("任务已中断");
            }
        }
        if (stopped) throw new LoopStoppedException("用户已停止任务");
    }

    public synchronized void pause(String reason) { pauseReasons.add(reason); }
    public synchronized void resume(String reason) {
        pauseReasons.remove(reason);
        notifyAll();
    }
    public synchronized boolean paused() { return !pauseReasons.isEmpty(); }
    public synchronized boolean pausedBy(String reason) { return pauseReasons.contains(reason); }
    public synchronized boolean stopped() { return stopped; }
    public synchronized void stop() {
        stopped = true;
        notifyAll();
    }
}
