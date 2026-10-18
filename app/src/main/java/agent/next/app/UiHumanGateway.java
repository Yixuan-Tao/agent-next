package agent.next.app;

import android.os.Handler;
import android.os.Looper;

import agent.next.brain.ConfirmationRejectedException;
import agent.next.brain.ConfirmationRequest;
import agent.next.brain.ConfirmationToken;
import agent.next.brain.HandoffRequest;
import agent.next.brain.HandoffSession;
import agent.next.brain.HumanGateway;
import agent.next.brain.HumanUnavailableException;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Routes risk confirmations to a main-thread dialog; never auto-approves. */
public final class UiHumanGateway implements HumanGateway {

    @FunctionalInterface
    public interface Presenter {
        void present(ConfirmationRequest request, Consumer<Boolean> decision);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Presenter presenter;

    public void setPresenter(Presenter presenter) {
        this.presenter = presenter;
    }

    @Override
    public ConfirmationToken confirm(ConfirmationRequest request) throws HumanUnavailableException {
        Objects.requireNonNull(request, "request");
        Presenter current = presenter;
        if (current == null) {
            throw new HumanUnavailableException("no confirmation UI");
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean approved = new AtomicBoolean(false);
        main.post(() -> current.present(request, decision -> {
            approved.set(Boolean.TRUE.equals(decision));
            latch.countDown();
        }));
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) {
                throw new HumanUnavailableException("confirmation timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new HumanUnavailableException("confirmation interrupted");
        }
        if (!approved.get()) {
            throw new ConfirmationRejectedException("user rejected the action");
        }
        return request.confirm(Instant.now(), Duration.ofMinutes(2));
    }

    @Override
    public HandoffSession handoff(HandoffRequest request) throws HumanUnavailableException {
        throw new HumanUnavailableException("handoff UI is not wired yet");
    }

    @Override
    public void endHandoff(HandoffSession session) {
    }

    @Override
    public void pause(String reason) {
    }

    @Override
    public void resume() {
    }

    @Override
    public void stop(String reason) {
    }
}
